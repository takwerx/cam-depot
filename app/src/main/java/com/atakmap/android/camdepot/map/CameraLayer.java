package com.atakmap.android.camdepot.map;

import com.atakmap.android.camdepot.model.Camera;
import com.atakmap.android.cot.detail.SensorDetailHandler;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoBounds;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.AtakMapView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The cameras on the map: markers, zoom gating, and the sensor FOV cone.
 *
 * <h3>Why markers are created once and never recreated</h3>
 *
 * A {@link SensorFOV} implements {@code OnGroupChangedListener} and
 * {@code OnVisibleChangedListener} on its parent marker, and detaches itself when
 * that marker leaves its group. So a refresh cycle that removes and re-adds markers
 * orphans every cone it drew — the classic "the FOV worked, then stopped" failure.
 *
 * <p>Everything here is therefore diff-based. {@link #show(List)} adds markers that
 * are new, removes ones no longer selected, and <em>mutates</em> the rest. Nothing
 * touches a marker that is merely being updated.
 *
 * <p>The same rule applies to the cone itself: setting azimuth metadata on the marker
 * draws nothing. {@link SensorFOV#setMetrics} is what fires {@code onMetricsChanged()}
 * and gets the renderer to redraw, so {@link #updateFov} calls it explicitly every
 * refresh.
 */
public final class CameraLayer {

    private static final String TAG = "CamDepotLayer";
    private static final String GROUP = "Cam Depot";
    private static final String UID_PREFIX = "camdepot-";
    /** Marker flag: sensor attributes written and the hideFov listener attached. */
    private static final String SENSOR_READY = "camdepotSensorReady";

    /**
     * Degrees to add to a camera's bearing when aiming its icon.
     *
     * <p>Corrects for how the artwork is drawn versus how ATAK rotates it. Verified on
     * device against the FOV line, which takes the raw azimuth and is therefore the
     * reference: the icon must end up pointing the same way the line leaves the marker.
     *
     * <p>Only the icon is offset. The sensor azimuth is never touched, so the bearing
     * line stays true regardless of what the artwork does.
     */
    private static final double ICON_HEADING_OFFSET = -90;

    /** Azimuth-line color: the same orange as the operator's own sensor marker. */
    private static final int LINE_COLOR = 0xFFFF7700;

    /**
     * A stills camera shows a short wedge instead of a long line, copying ATAK's own
     * Quick Pic.
     *
     * <p>{@code ImageContainer.populateLocation} does exactly this when a photo with a
     * {@code GPSImgDirection} is opened:
     *
     * <pre>
     *   float hfov = ExifHelper.getExtraFloat(tiff, "HorizontalFOV", 20.0f);
     *   sensorFOV.setMetrics(direction, hfov, 1000.0f);
     * </pre>
     *
     * <p>The distinction that matters: a streaming camera's line answers <em>how far
     * can it see</em>, so it runs to the horizon. This answers only <em>which way is
     * it looking</em>, for a fixed oblique camera whose icon cannot point. A short
     * wedge says that and then stops making claims — the range is deliberately not a
     * statement about coverage.
     *
     * <p>It does not reintroduce the wedge-soup the bearing line was designed to
     * avoid. That was 1,600 wedges drawn at once across every fire camera; this is one,
     * only while its picture is open.
     */
    private static final double STILL_FOV_DEGREES = 20;
    private static final double STILL_FOV_RANGE_M = 1000;

    /**
     * Resolution to fly to on "Go to", in meters per pixel — close enough to see the
     * camera and its surroundings without being on top of it.
     */
    /**
     * How close "Go to" arrives, in meters per pixel.
     *
     * <p>This was 30, which is roughly a seven-mile scale bar — and an operator who
     * had already zoomed in was usually AT 30 or closer, so Go to panned exactly onto
     * the camera and changed the view not at all. It centered correctly and looked
     * like it had done nothing, because arriving somewhere is not visible if the
     * altitude never changes. 8 puts the camera and a couple of miles around it on
     * screen, which is close enough to read as having gone there. 3 is closer again,
     * about a half-mile scale bar, which is what the operator asked for once they had
     * seen 8 on the device: close enough to see the road the camera is watching.
     */
    private static final double GOTO_RESOLUTION = 3;

    /**
     * Line length in meters until the operator changes it.
     *
     * <p>60 km, matching {@link SensorDetailHandler#MAX_SENSOR_RANGE} and the operator's
     * own sensor marker. A lookout on a ridge sees a very long way, and a line that
     * stops short of the horizon understates what the camera actually covers.
     */
    public static final double DEFAULT_RANGE_M = 60000;
    /**
     * Hard ceiling on markers drawn at once.
     *
     * <p>Originally 300, set in a hurry after 4,643 markers built in one blocking pass
     * ANR'd ATAK (2026-08-25). That was far too conservative: the problem was never
     * the count, it was doing all the work in one go. Creation is now spread across
     * frames ({@link #BATCH}), so this ceiling exists only to keep the map readable
     * and the GL load sane — and it must still be <em>reported</em>, or a truncated
     * map silently reads as the whole picture.
     */
    public static final int MAX_MARKERS = 2500;

    /**
     * Markers added per pass. Small enough to fit comfortably in a frame, large
     * enough that a few thousand cameras land in well under a second.
     */
    private static final int BATCH = 50;
    /**
     * A frame's worth of pause between batches.
     *
     * <p>Re-posting with no delay put the next batch straight back on the looper, so
     * the main thread ran marker work end to end and input had to fight for a slot.
     * Yielding a frame costs nothing an operator can see -- the map fills in over a
     * few hundred milliseconds either way -- and keeps the UI answering. Confirmed
     * from the ANR captured 2026-08-28 10:40, whose main thread was inside
     * MapGroup.addItem under addTick.
     */
    private static final long BATCH_PAUSE_MS = 16;

    private final MapView mapView;
    private final android.content.Context pluginContext;
    private final MapGroup group;
    /** ATAK's own Quick Pic marker icon, for cameras that serve a still only. */
    private Icon stillIcon;

    private final Map<String, Marker> markers = new HashMap<>();
    /**
     * Camera id -> the ConnectionEntry UID already registered for it.
     *
     * <p>Markers are destroyed and rebuilt whenever the viewport or a filter changes.
     * Without this, every rebuild registered another entry with ATAK's VideoManager
     * for the same camera -- hundreds of duplicates accumulating in the operator's
     * video list, and pointless work on the UI thread each time.
     */
    private final Map<String, String> videoUids = new HashMap<>();
    /** Video entries built this pass, registered in one batch by {@link #addTick}. */
    private final List<gov.tak.api.video.ConnectionEntry> pendingEntries =
            new ArrayList<>();
    private final Map<String, Camera> shown = new HashMap<>();
    /** Cameras waiting for a marker; drained by {@link #addTick}. */
    private final List<Camera> pending = new ArrayList<>();
    /** Marker ids waiting to be taken off the map; drained by {@link #removeTick}. */
    private final List<String> removing = new ArrayList<>();
    /** Everything the panel's filters selected, whether or not it is on screen. */
    private final List<Camera> selected = new ArrayList<>();
    /** How much of {@link #selected} the last pass could not fit. */
    private int omitted;

    /**
     * Cameras draw at or below this resolution, in meters per pixel. Larger means
     * more zoomed out, so this is a "no further out than" limit.
     *
     * <p>Default is roughly county scale — far enough out to be useful, close enough
     * that a whole-state view is not covered in icons. The operator resets it by
     * zooming to where they want cameras to start and pressing "Use this zoom",
     * which avoids them having to reason about scale at all.
     */
    /** Told when a stills camera is tapped on the map, so its picture can open. */
    public interface OnStillTapped {
        void onStillTapped(Camera c);
    }

    private OnStillTapped stillTapped;

    public void setOnStillTapped(OnStillTapped l) {
        this.stillTapped = l;
    }

    /**
     * Tapping a stills camera on the map opens its picture.
     *
     * <p>A camera that streams has the radial's video button and needs nothing extra.
     * A stills camera had no way in from the map at all: its radial is the sensor one,
     * whose video button is correctly greyed out, and ATAK offers no "show me the
     * picture" action for a marker.
     *
     * <p>It is not an attachment and deliberately so. An attachment is a stored
     * artifact belonging to the marker; a camera still is a live view where the only
     * frame worth having is the current one. This reaches out and fetches on each open,
     * the same as the panel does, and leaves nothing on disk.
     *
     * <p>The listener does not consume the event, so ATAK's radial still opens
     * alongside — nothing that worked before stops working.
     */
    private final com.atakmap.android.maps.MapEventDispatcher.MapEventDispatchListener
            itemClick = new com.atakmap.android.maps.MapEventDispatcher
                    .MapEventDispatchListener() {
        @Override
        public void onMapEvent(com.atakmap.android.maps.MapEvent event) {
            if (event == null || stillTapped == null)
                return;
            final MapItem it = event.getItem();
            if (it == null || it.getUID() == null
                    || !it.getUID().startsWith(UID_PREFIX))
                return;
            final Camera c = shown.get(
                    it.getUID().substring(UID_PREFIX.length()));
            if (c != null && !c.hasStream())
                stillTapped.onStillTapped(c);
        }
    };

    private double maxResolution = 500;
    private double rangeMeters = DEFAULT_RANGE_M;
    private boolean gateEnabled = true;

    private final android.os.Handler main =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * <strong>Runs on the GL render thread, not the UI thread.</strong> ATAK dispatches
     * map-moved through {@code GLMapView.dispatchCameraChanged} over JNI. Mutating map
     * items from inside a render pass is how you get a native SIGSEGV with no Java
     * stack trace, so the gate is posted to the main thread and coalesced — during a
     * pinch this fires every frame.
     */
    private final AtakMapView.OnMapMovedListener moved =
            new AtakMapView.OnMapMovedListener() {
                @Override
                public void onMapMoved(AtakMapView view, boolean animate) {
                    main.removeCallbacks(gateTick);
                    main.postDelayed(gateTick, 150);
                }
            };

    private final Runnable gateTick = new Runnable() {
        @Override
        public void run() {
            // Panning or zooming changes which cameras are on screen, so this is a
            // redraw, not just a visibility flip.
            drawVisible();
        }
    };

    public CameraLayer(MapView mapView, android.content.Context pluginContext) {
        this.mapView = mapView;
        this.pluginContext = pluginContext;
        MapGroup g = mapView.getRootGroup().findMapGroup(GROUP);
        if (g == null)
            g = mapView.getRootGroup().addGroup(GROUP);
        this.group = g;
        mapView.addOnMapMovedListener(moved);
        mapView.getMapEventDispatcher().addMapEventListener(
                com.atakmap.android.maps.MapEvent.ITEM_CLICK, itemClick);
    }

    public void dispose() {
        main.removeCallbacks(gateTick);
        main.removeCallbacks(addTick);
        main.removeCallbacks(removeTick);
        mapView.removeOnMapMovedListener(moved);
        mapView.getMapEventDispatcher().removeMapEventListener(
                com.atakmap.android.maps.MapEvent.ITEM_CLICK, itemClick);
        clear();
        mapView.getRootGroup().removeGroup(group);
    }

    // ---- zoom gating ------------------------------------------------------

    /** @param metersPerPixel cameras draw at or below this; larger means zoomed out */
    public void setMaxResolution(double metersPerPixel) {
        this.maxResolution = metersPerPixel;
        applyZoomGate();
    }

    public double getMaxResolution() {
        return maxResolution;
    }

    public void setGateEnabled(boolean enabled) {
        this.gateEnabled = enabled;
        applyZoomGate();
    }

    public boolean isWithinZoom() {
        return !gateEnabled || mapView.getMapResolution() <= maxResolution;
    }

    private void applyZoomGate() {
        final boolean visible = isWithinZoom();
        for (Marker m : markers.values()) {
            if (m.getVisible() != visible)
                m.setVisible(visible);
        }
    }

    // ---- markers ----------------------------------------------------------

    /**
     * Make the map show exactly {@code cameras}: add, remove, and update in place.
     */
    /**
     * Take the panel's selection. What actually gets drawn is decided by
     * {@link #drawVisible()}, from the current viewport.
     */
    public void show(List<Camera> cameras) {
        selected.clear();
        selected.addAll(cameras);
        drawVisible();
    }

    /**
     * Draw the selected cameras that are on screen.
     *
     * <p>Keyed to the viewport rather than to a global "nearest N" because that is
     * what an operator expects: zoom in and everything in front of you is drawn, since
     * a smaller view holds fewer cameras. A fixed nearest-N picked once at filter time
     * does the opposite — zooming in never reveals anything new, because the same 2500
     * were chosen when the map was somewhere else entirely.
     *
     * <p>The cap survives only for the zoomed-out case, where a viewport can still hold
     * thousands.
     */
    public void drawVisible() {
        final GeoBounds view = padded(mapView.getBounds());
        final List<Camera> want = new ArrayList<>();
        for (Camera c : selected) {
            if (view == null || view.intersects(c.lat, c.lon, c.lat, c.lon))
                want.add(c);
        }
        omitted = Math.max(0, want.size() - MAX_MARKERS);
        final List<Camera> draw = want.size() > MAX_MARKERS
                ? new ArrayList<>(want.subList(0, MAX_MARKERS)) : want;
        apply(draw);
    }

    /** How many on-screen cameras the cap left out last pass. */
    public int getOmitted() {
        return omitted;
    }

    /**
     * Grow the viewport by a margin so a small pan does not strobe markers in and out
     * at the edge of the screen.
     */
    private GeoBounds padded(GeoBounds b) {
        if (b == null)
            return null;
        final double dLat = Math.abs(b.getNorth() - b.getSouth()) * 0.25;
        final double dLon = Math.abs(b.getEast() - b.getWest()) * 0.25;
        return new GeoBounds(b.getSouth() - dLat, b.getWest() - dLon,
                b.getNorth() + dLat, b.getEast() + dLon);
    }

    private void apply(List<Camera> cameras) {
        final Set<String> wanted = new HashSet<>(cameras.size() * 2);
        for (Camera c : cameras)
            wanted.add(c.id);

        main.removeCallbacks(addTick);
        pending.clear();

        // Removals are batched for the same reason additions are. Ticking a filter
        // can drop thousands of markers at once, and doing every removeItem in one
        // pass blocks the UI thread long enough to feel like a hang -- which is what
        // "serious delay checking the fire box" was. Immediacy was the wrong trade.
        main.removeCallbacks(removeTick);
        removing.clear();
        for (String id : markers.keySet()) {
            if (!wanted.contains(id))
                removing.add(id);
        }
        if (!removing.isEmpty())
            main.post(removeTick);

        for (Camera c : cameras) {
            final Marker m = markers.get(c.id);
            if (m == null) {
                pending.add(c);
            } else {
                update(m, c);
                shown.put(c.id, c);
                updateFov(c);
            }
        }
        if (!pending.isEmpty())
            main.post(addTick);
        applyZoomGate();
    }

    /**
     * Adds queued markers a batch at a time.
     *
     * <p>Building every marker in one pass is what made ATAK stop responding: each is
     * a {@code new Marker} plus a {@code group.addItem} on the UI thread, and a few
     * thousand of those exceed Android's ANR window. Spreading them over frames keeps
     * the panel usable while the map fills in.
     */
    private final Runnable removeTick = new Runnable() {
        @Override
        public void run() {
            int n = 0;
            while (!removing.isEmpty() && n++ < BATCH) {
                remove(removing.remove(removing.size() - 1));
            }
            if (!removing.isEmpty())
                main.postDelayed(this, BATCH_PAUSE_MS);
        }
    };

    private final Runnable addTick = new Runnable() {
        @Override
        public void run() {
            int n = 0;
            while (!pending.isEmpty() && n++ < BATCH) {
                final Camera c = pending.remove(0);
                if (markers.containsKey(c.id))
                    continue;
                final Marker m = create(c);
                markers.put(c.id, m);
                group.addItem(m);
                update(m, c);
                shown.put(c.id, c);
                m.setVisible(isWithinZoom());
            }
            // Register video entries ONCE, when the queue is empty -- not per batch.
            //
            // Every addConnectionEntries call broadcasts REFRESH_HIERARCHY whatever
            // the persist flag says, and that rebuilds ATAK's Overlay Manager on the
            // main thread. Per batch, a full map was 21 rebuilds rather than one, and
            // the catalog going from 13,698 cameras to 30,393 is what pushed it past
            // the ANR window. The comment on flushVideoEntries always said one call,
            // not one per camera; this makes the batching match it.
            if (pending.isEmpty()) {
                flushVideoEntries();
            } else {
                main.postDelayed(this, BATCH_PAUSE_MS);
            }
        }
    };

    public void clear() {
        main.removeCallbacks(addTick);
        main.removeCallbacks(removeTick);
        pending.clear();
        removing.clear();
        selected.clear();
        omitted = 0;
        for (String id : new ArrayList<>(markers.keySet()))
            remove(id);
        shown.clear();
    }

    /**
     * Forget the video entries registered from the previous catalog.
     *
     * <p>For Sync, and only Sync. {@code videoUids} maps a camera to the
     * ConnectionEntry UID already registered with ATAK's VideoManager, and both
     * registration paths short-circuit on it -- deliberately, because re-registering
     * on every marker rebuild is a per-pan cost for no gain.
     *
     * <p>That is right until the catalog itself changes underneath. When Cam Depot's
     * live host moved on 2026-08-31, a device that pressed Sync kept playing from the
     * old host: the entry was still held, so the plugin never rebuilt it, and only
     * restarting ATAK -- which empties VideoManager -- fixed it. Sync exists so that
     * restart is not needed, and a moved stream URL is the exact case it was written
     * for.
     *
     * <p>Not called from {@link #clear()}, which also runs on an ordinary state
     * switch. Forgetting there would re-register a state's entries every time the
     * operator moved away and back.
     *
     * <p>The removal runs off the UI thread. VideoManager persists to disk on the
     * caller's thread, which is why entries are added with the batched
     * {@code addConnectionEntries(list, false)} in the first place.
     */
    public void forgetVideoEntries() {
        if (videoUids.isEmpty())
            return;
        final Set<String> stale = new HashSet<>(videoUids.values());
        videoUids.clear();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    com.atakmap.android.video.manager.VideoManager.getInstance()
                            .removeConnectionEntries(stale);
                    Log.d(TAG, "forgot " + stale.size() + " video entries for a sync");
                } catch (LinkageError | RuntimeException e) {
                    Log.w(TAG, "could not remove stale video entries", e);
                }
            }
        }, "camdepot-video-forget").start();
    }

    private void remove(String id) {
        // Deliberately NOT hideFov(id): ATAK's own OnGroupChangedListener drops the
        // SensorFOV when the marker leaves the group, so writing hideFov here only
        // buys a metadata dispatch and a group scan per removal -- during viewport
        // churn that is 120 of them a frame, for a marker that is going away.
        //
        // `showing` only, never `requested`: the geometry is going away, the
        // operator's request is not. update() puts the line back when the camera
        // scrolls in again.
        showing.remove(id);
        final Marker m = markers.remove(id);
        if (m != null) {
            m.removeOnMetadataChangedListener(SensorDetailHandler.HIDE_FOV, fovWatch);
            group.removeItem(m);
        }
        shown.remove(id);
    }

    /**
     * Give a marker what ATAK's radial menu needs to enable its video button.
     *
     * <p>Built from a working CoT supplied by the operator — an ALERT Wildfire camera
     * that plays in ATAK today:
     *
     * <pre>
     *   &lt;__video uid="114aed35-…" url="https://…/wildfire?id=Axis-Weed2"&gt;
     *     &lt;ConnectionEntry networkTimeout="12000" uid="114aed35-…" path=""
     *        protocol="raw" bufferTime="-1" address="https://…/wildfire?id=Axis-Weed2"
     *        port="-1" rtspReliable="0" ignoreEmbeddedKLV="false" alias="Weed 2"/&gt;
     *   &lt;/__video&gt;
     * </pre>
     *
     * <p>Two things that are not guessable from the class signatures, and which this
     * plugin got wrong until that CoT arrived:
     *
     * <ul>
     *   <li><strong>The protocol is {@code RAW}</strong>, with the entire URL as the
     *       address and {@code port = -1}. Not HTTPS, and not split into host and
     *       path — {@code createConnectionEntryFromUrl} parses a URL the ordinary way
     *       and does not produce this shape.</li>
     *   <li>The radial's gate is the marker's {@code videoUID} metadata
     *       ({@code menus/b-m-p-s-p-loc.xml}: {@code disabled='!{${videoUID}}'}), and
     *       it must name an entry the VideoManager actually holds.</li>
     * </ul>
     */
    private void attachVideo(Marker m, Camera c) {
        if (!c.hasStream())
            return;
        // Reuse this camera's UID across marker rebuilds, and skip re-registering when
        // the VideoManager still holds it.
        //
        // An earlier version short-circuited on the UID alone and was right to be
        // distrusted: an entry that had gone from the VideoManager left the marker's
        // videoUID pointing at nothing, which ATAK reports as "invalid format" -- it
        // reads like a bad URL and is not one. So the check is not "did we make a UID
        // once", it is "does the manager hold it right now". That is a HashMap lookup,
        // and it turns re-registration from a per-pan cost into a once-per-session one.
        final String known = videoUids.get(c.id);
        if (known != null && holdsEntry(known)) {
            m.setMetaString("videoUID", known);
            m.setMetaString("videoUrl", c.stream);
            return;
        }
        try {
            final gov.tak.api.video.ConnectionEntry ce = buildEntry(c);
            if (ce == null)
                return;
            // Queued, not registered here. See flushVideoEntries().
            pendingEntries.add(ce);
            videoUids.put(c.id, ce.getUID());
            m.setMetaString("videoUID", ce.getUID());
            m.setMetaString("videoUrl", c.stream);
        } catch (LinkageError | RuntimeException e) {
            // Video is a bonus; a plugin must not fail to draw a camera over it.
            Log.w(TAG, "could not attach video for " + c.id, e);
        }
    }

    /**
     * The one shape of {@code ConnectionEntry} that actually plays these streams.
     *
     * <p>protocol=raw with the whole URL as the address, port -1.
     *
     * <p>This is the shape from the operator's working CoT, and it is confirmed
     * playing on device. It was briefly "corrected" to let ATAK infer the protocol
     * from the URL, on the theory that raw suited MJPEG and HLS needed HTTPS. That
     * broke playback outright. The theory was wrong; raw is right for these too. Do
     * not change it again without a camera that demonstrably fails under raw and
     * plays under something else.
     */
    private gov.tak.api.video.ConnectionEntry buildEntry(Camera c) {
        // https only, and checked here because this is where a catalog string stops
        // being data and becomes an MRL that libVLC will dial.
        //
        // Http refuses a non-https request by design and CameraStore documents that
        // as deliberate, but the video path never went through Http: the URL is
        // handed to ATAK's player verbatim with protocol=RAW, so nothing parses or
        // vets it. The catalog is built from ~25 third-party government APIs we do
        // not control, and the publisher's liveness probe only establishes that a
        // URL answers, not what scheme or host it is. Publisher-side validation is
        // the first line; this is the one that matters on the device.
        if (!isHttps(c.stream)) {
            Log.w(TAG, "refusing a non-https stream for " + c.id);
            return null;
        }
        String uid = videoUids.get(c.id);
        if (uid == null)
            uid = java.util.UUID.randomUUID().toString();
        final gov.tak.api.video.ConnectionEntry ce =
                new gov.tak.api.video.ConnectionEntry(c.label(), c.stream);
        ce.setUID(uid);
        ce.setAddress(c.stream);
        ce.setPort(-1);
        ce.setPath("");
        ce.setNetworkTimeout(12000);
        ce.setBufferTime(-1);
        ce.setRtspReliable(0);
        ce.setIgnoreEmbeddedKLV(false);
        // Not temporary: ATAK may prune temporary entries, and a pruned entry is
        // indistinguishable from a broken stream from the operator's side.
        ce.setTemporary(false);
        setRawProtocol(ce);
        return ce;
    }

    /** True only for an https URL. Nothing else is handed to the video player. */
    private static boolean isHttps(String url) {
        return url != null && url.regionMatches(true, 0, "https://", 0, 8);
    }

    /**
     * The registered, playable entry for a camera, for anything outside the radial.
     *
     * <p>The panel used to build its own with
     * {@code StreamManagementUtils.createConnectionEntryFromUrl}, which splits the URL
     * into host, port and path the ordinary way and lets ATAK infer the protocol. That
     * is precisely the shape {@link #buildEntry} records as not playing. So the radial
     * played a camera and the panel's own Live video button, on the same camera, did
     * not -- two code paths that looked equivalent and were not. There is now one.
     *
     * <p>Registers immediately rather than queueing: the caller is about to hand the
     * uid to the video player, and an entry the VideoManager does not hold yet reads
     * to the operator as a broken stream.
     *
     * @return null when the camera has no stream, or registration failed
     */
    public gov.tak.api.video.ConnectionEntry videoEntry(Camera c) {
        if (c == null || !c.hasStream())
            return null;
        try {
            final String known = videoUids.get(c.id);
            if (known != null) {
                final gov.tak.api.video.ConnectionEntry held =
                        com.atakmap.android.video.manager.VideoManager.getInstance()
                                .getConnectionEntry(known);
                if (held != null)
                    return held;
            }
            final gov.tak.api.video.ConnectionEntry ce = buildEntry(c);
            videoUids.put(c.id, ce.getUID());
            com.atakmap.android.video.manager.VideoManager.getInstance()
                    .addConnectionEntries(
                            java.util.Collections.singletonList(ce), false);
            return ce;
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not build a video entry for " + c.id, e);
            return null;
        }
    }

    /**
     * Color a marker to say whether its bearing line is on, and repaint it.
     *
     * <p>Everything about this hinges on {@code Marker.setColor} rather than
     * {@code setMetaInteger("color", ...)}:
     *
     * <pre>
     *   public void setColor(int i) {
     *       if (i != getColor()) { setMetaInteger("color", i); refresh(...); }
     *   }
     * </pre>
     *
     * <p>The plain metadata write does not refresh, so the color sat in the metadata
     * unpainted until something else forced a refresh — which is what ATAK's own
     * sensor controls do via {@code MapItem.persist()}. That is why the marker turned
     * orange the first time a bearing was shown and never went back: the "on" was an
     * accident of ATAK refreshing, and the "off" had nothing to trigger a repaint.
     * {@code removeMetaData} did not work either — {@code getColor()} already returns
     * -1 when the key is absent, so there was no change for the renderer to notice.
     *
     * <p>-1 is white, which is a no-op tint, so the artwork shows as drawn. The icon
     * itself is never touched: swapping it replaced the sensor icon with the plain
     * north-facing arrow, which is not what "remove the tint" means.
     */
    private void applyTint(Marker m, Camera c, boolean on) {
        if (c == null || !c.hasFov())
            return;
        try {
            m.setColor(on ? baseColor(c) : -1);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not set the marker tint for " + c.id, e);
        }
    }

    /** Offline grey, fire orange, DOT/FAA blue. */
    private static int baseColor(Camera c) {
        return c.offline ? 0xFF808080 : (c.fire ? 0xFFFF6600 : 0xFF33B5E5);
    }

    /** Does ATAK's VideoManager still hold this entry? An in-memory map lookup. */
    private static boolean holdsEntry(String uid) {
        try {
            return com.atakmap.android.video.manager.VideoManager.getInstance()
                    .getConnectionEntry(uid) != null;
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    /**
     * Register a frame's worth of video entries in one call, without persisting them.
     *
     * <p>Both halves of this matter, and both were measured on device from ANR traces
     * rather than guessed at. Nine ANRs were captured; eight were this code path.
     *
     * <p><strong>Never persist.</strong> {@code addConnectionEntry} defaults to
     * writing each entry to its own XML file under {@code videos/.entries/} —
     * synchronously, on the calling thread, which for us is the UI thread inside
     * {@link #addTick}. That is a file write per camera per marker rebuild, and it
     * ANR'd ATAK in two separate places: on the write, and again at the next ATAK
     * launch, when {@code VideoManager.init} read all <strong>4,744</strong>
     * accumulated files back on the main thread before the map would come up. An
     * entry only has to be in the manager's map for the radial's {@code videoUID}
     * gate to resolve, so the two-argument form with {@code persist = false} keeps
     * the feature and drops the disk entirely.
     *
     * <p><strong>One call, not one per camera.</strong> Each call broadcasts
     * {@code REFRESH_HIERARCHY} whatever the persist flag says, and a broadcast per
     * camera is a fresh Overlay Manager rebuild 120 times a frame. Batching makes it
     * one.
     */
    private void flushVideoEntries() {
        if (pendingEntries.isEmpty())
            return;
        final List<gov.tak.api.video.ConnectionEntry> batch =
                new ArrayList<>(pendingEntries);
        pendingEntries.clear();
        try {
            com.atakmap.android.video.manager.VideoManager.getInstance()
                    .addConnectionEntries(batch, false);
            Log.d(TAG, "registered " + batch.size() + " video entries (no persist)");
        } catch (LinkageError | RuntimeException e) {
            // Video is a bonus; a plugin must not fail to draw a camera over it.
            Log.w(TAG, "could not register video entries", e);
        }
    }

    /**
     * Force {@code protocol="raw"} on a ConnectionEntry.
     *
     * <p>Reflection, for a language reason rather than a design one: {@code Protocol}
     * is a public enum nested inside {@code ConnectionEntryBase}, which is
     * package-private, so it cannot be named from this package at compile time even
     * though every constant is public.
     */
    private static void setRawProtocol(gov.tak.api.video.ConnectionEntry ce) {
        try {
            // Find the setter by NAME, and take the enum type from its own
            // parameter. Never name the enum class.
            //
            // Class.forName("gov.tak.api.video.ConnectionEntryBase$Protocol") is
            // wrong on an obfuscated build: ATAK 5.6 renames that enum to
            // gov.tak.api.video.a$b, so the lookup either misses or resolves to a
            // class that does not match setProtocol's signature --
            // "NoSuchMethodException: ConnectionEntry.setProtocol [class
            // gov.tak.api.video.a$b]" on the tak.gov build. Asking the method what
            // it takes cannot be wrong, whatever the obfuscator renamed.
            java.lang.reflect.Method setter = null;
            for (java.lang.reflect.Method m
                    : gov.tak.api.video.ConnectionEntry.class.getMethods()) {
                if ("setProtocol".equals(m.getName())
                        && m.getParameterTypes().length == 1) {
                    setter = m;
                    break;
                }
            }
            if (setter != null) {
                final Class<?> proto = setter.getParameterTypes()[0];
                final Object[] all = proto.getEnumConstants();
                Object raw = null;
                if (all != null) {
                    // toString survives obfuscation where the constant name does
                    // not; the enum prints itself lowercase.
                    for (Object v : all) {
                        if ("raw".equalsIgnoreCase(String.valueOf(v))) {
                            raw = v;
                            break;
                        }
                    }
                    if (raw == null && all.length > 0)
                        raw = all[0];   // RAW is first in 5.6, 5.7 and 5.8
                }
                if (raw != null)
                    setter.invoke(ce, raw);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "could not force RAW protocol; leaving " + ce.getProtocol(), e);
        }

        // Whatever happened above, leave a COHERENT entry: ATAK builds the MRL as
        // protocol + "://" + address, so an address that is already a full URL
        // doubles the scheme when the protocol is not raw.
        if (!"raw".equalsIgnoreCase(String.valueOf(ce.getProtocol()))) {
            final String addr = ce.getAddress();
            final int mark = addr == null ? -1 : addr.indexOf("://");
            if (mark > 0) {
                Log.w(TAG, "RAW unavailable; handing " + ce.getProtocol()
                        + " a scheme-less address so the URL is not doubled");
                ce.setAddress(addr.substring(mark + 3));
            }
        }
    }

    private Marker create(Camera c) {
        final Marker m = new Marker(new GeoPoint(c.lat, c.lon), UID_PREFIX + c.id);
        // A generic sensor point: ATAK has an icon for it, so the plugin does not
        // have to ship one to be usable on day one.
        // The radial menu is chosen by CoT type: ATAK's MenuMapAdapter looks up
        // assets/menus/<type>.xml and falls back to menus/default_item.xml.
        //
        // Every camera gets the sensor type, including those with no bearing. That was
        // briefly "fixed" by giving bearingless cameras b-m-p-c so they would not offer
        // FOV controls they cannot use -- but ATAK does not recognize that type: the
        // details pane reads "Not Recognized [b-m-p-c]" and the marker loses its icon.
        // Unusable controls on a working marker beat a broken one.
        m.setType("b-m-p-s-p-loc");
        // Every marker declares its FOV off, including cameras with no bearing.
        //
        // ATAK's sensor menu reads selected='!{${hideFov}}', so an ABSENT value renders
        // the button as already switched on. Cameras with no bearing skip the sensor
        // setup entirely, so they never wrote it and their FOV button came up
        // preselected -- looking like a state we had chosen rather than one we had
        // simply never set.
        // hideFov is PRESENCE: set means hidden, absent means shown. A camera whose
        // bearing was asked for while it was off the map must not be born hidden, or
        // the request is silently lost the moment it is finally drawn.
        if (!requested.contains(c.id))
            m.setMetaBoolean(SensorDetailHandler.HIDE_FOV, true);
        m.setMetaBoolean("readiness", true);
        m.setMetaBoolean("archive", false);
        m.setMetaString("how", "m-g");
        m.setMovable(false);
        setDirectionalIcon(m, c);
        aimIcon(m, c);
        m.setClickable(true);
        attachVideo(m, c);
        attachSensor(m, c);
        if (c.hasFov()) {
            // A listener, not a Shape. This is what lets ATAK's own radial FOV button
            // work without pre-building GL geometry for every camera in view.
            m.addOnMetadataChangedListener(SensorDetailHandler.HIDE_FOV, fovWatch);
            m.setMetaBoolean(SENSOR_READY, true);
        }
        return m;
    }

    /**
     * Describe where the camera is looking, as marker metadata, and let ATAK draw it.
     *
     * <p>Modelled attribute-for-attribute on a sensor marker the operator built in
     * ATAK and broadcast, captured off the wire:
     *
     * <pre>
     *   &lt;sensor elevation='0' vfov='45' roll='0'
     *           azimuth='270' fov='0' range='60000'
     *           fovRed='1.0' fovGreen='1.0' fovBlue='1.0' fovAlpha='0.0'
     *           strokeWeight='5.0' strokeColor='-35072'
     *           rangeLines='25' rangeLineStrokeWeight='1.0'
     *           rangeLineStrokeColor='-16777216'
     *           displayMagneticReference='0'/&gt;
     * </pre>
     *
     * <p><strong>fov=0 and fovAlpha=0 are deliberate.</strong> They switch the filled
     * wedge off and leave a stroked <em>azimuth line</em> — the direction the camera
     * points, not a translucent pie slice. That is the operator's choice and it is the
     * right one here: sixteen hundred wedges is soup, sixteen hundred bearing lines is
     * a map you can read. The line is drawn by {@code strokeWeight} and
     * {@code strokeColor}, which this plugin previously did not set at all, which is
     * why nothing appeared however correct the azimuth was.
     *
     * <p>Handing the geometry to ATAK also hands over its lifecycle. The old approach
     * built {@code SensorFOV} objects and pushed metrics on a timer, which silently
     * detaches when a marker changes group — the "worked, then stopped" failure. ATAK
     * owning it removes that rather than patching it, and the radial's own sensor
     * buttons start working because they act on this same state.
     *
     * <p>Only cameras with real pointing data get any of this: Caltrans reports
     * azimuth 0 for every camera in the state, and a confident line pointing due north
     * from 2,900 cameras would be a lie.
     */
    private void attachSensor(Marker m, Camera c) {
        if (!c.hasFov())
            return;
        // Integers, not doubles, and this is not a style choice.
        //
        // SensorDetailHandler.toCotDetail reads azimuth, fov, range, elevation, vfov
        // and roll with getMetaInteger. A double under any of those keys throws
        // ClassCastException the moment ATAK tries to build the sensor detail --
        // caught and logged, so nothing crashes, but the sensor CoT is never
        // produced. Seen on device against Sierra Peak 1:
        //
        //   java.lang.ClassCastException: java.lang.Double cannot be cast to
        //     java.lang.Integer
        //     at DefaultMetaDataHolder.getMetaInteger
        //     at SensorDetailHandler.toCotDetail
        //
        // ATAK rounds these itself in addFovToMap, so integers are its own
        // convention rather than a loss we are choosing. The SensorFOV still gets
        // the precise bearing through setMetrics().
        m.setMetaInteger(SensorDetailHandler.AZIMUTH_ATTRIBUTE,
                (int) Math.round(c.pan));
        m.setMetaInteger(SensorDetailHandler.RANGE_ATTRIBUTE,
                (int) Math.round(Math.min(rangeMeters,
                        SensorDetailHandler.MAX_SENSOR_RANGE)));

        // The wedge, off. See the class comment: we want the line, not the slice.
        m.setMetaInteger(SensorDetailHandler.FOV_ATTRIBUTE, 0);
        m.setMetaDouble(SensorDetailHandler.FOV_ALPHA, 0.0);
        m.setMetaDouble(SensorDetailHandler.FOV_RED, 1.0);
        m.setMetaDouble(SensorDetailHandler.FOV_GREEN, 1.0);
        m.setMetaDouble(SensorDetailHandler.FOV_BLUE, 1.0);

        // The line itself.
        m.setMetaDouble(SensorDetailHandler.STROKE_WEIGHT, 5.0);
        m.setMetaInteger(SensorDetailHandler.STROKE_COLOR, LINE_COLOR);
        // Range rings off; the line carries its bearing as a label instead.
        m.setMetaInteger(SensorDetailHandler.RANGE_LINES_ATTRIBUTE, 0);
        m.setMetaBoolean(SensorDetailHandler.DISPLAY_LABELS, true);
        m.setMetaDouble(SensorDetailHandler.RANGE_LINES_STROKE_WEIGHT, 1.0);
        m.setMetaInteger(SensorDetailHandler.RANGE_LINES_STROKE_COLOR, 0xFF000000);

        m.setMetaInteger(SensorDetailHandler.VFOV_ATTRIBUTE, 45);
        m.setMetaInteger(SensorDetailHandler.ROLL_ATTRIBUTE, 0);
        m.setMetaInteger(SensorDetailHandler.ELEVATION_ATTRIBUTE,
                Double.isNaN(c.tilt) ? 0 : (int) Math.round(c.tilt));
        m.setMetaInteger(SensorDetailHandler.MAG_REF_ATTRIBUTE, 0);

        // Off until asked for; a line from every camera at load would bury the map.
        m.setMetaBoolean(SensorDetailHandler.HIDE_FOV, true);

        // The SensorFOV object itself is NOT created here.
        //
        // It used to be, so ATAK's own radial FOV button would work on a marker the
        // operator had never opened. That cost a Shape and its GL geometry for every
        // fire camera in view — ~1,600 in California — to support at most a handful of
        // bearings actually being shown. Measured at 141 MB of GL memory on a 3.6 GB
        // phone, which is most of why the plugin bogged ATAK down.
        //
        // showFov() creates it on demand instead. The metadata above is still written,
        // so the radial reads the right state; ATAK's own toggle goes through
        // getOrAddSensorFov, which creates one if it is missing.
    }

    /**
     * Get-or-create this marker's SensorFOV and (re)apply the line styling.
     *
     * <p>Called on creation <em>and</em> every time a bearing is shown, deliberately.
     * Styling only at creation left two cameras on the same ridge looking different:
     * markers are destroyed and rebuilt as the viewport and filters change, and a
     * marker that merely got {@code update()}d never ran the styling, so ATAK's default
     * thin stroke showed instead of the 5.0 line. Re-asserting is cheap —
     * {@code addFovToMap} is get-or-add — and it makes the appearance independent of
     * how a given marker happened to arrive.
     */
    private void styleFov(Marker m, Camera c, boolean visible) {
        if (c == null || !c.hasFov())
            return;
        final boolean still = !c.hasStream();
        try {
            final com.atakmap.android.maps.SensorFOV f =
                    SensorDetailHandler.addFovToMap(m,
                            c.pan,
                            still ? STILL_FOV_DEGREES : 0,  // wedge, or a bearing line
                            still ? STILL_FOV_RANGE_M
                                  : Math.min(rangeMeters,
                                          SensorDetailHandler.MAX_SENSOR_RANGE),
                            // Line: alpha 0, no fill, per the operator's CoT.
                            // Wedge: translucent white, like Quick Pic's.
                            still ? new float[] { 1f, 1f, 1f, 0.25f }
                                  : new float[] { 1f, 1f, 1f, 0f },
                            // The sixth argument is VISIBLE, not labels.
                            //
                            // It was read as "labels" and hard-coded false, so every
                            // call -- create, show, and every refresh -- ended in
                            // addFovToMap's own setVisible(false). The line was built
                            // correctly and then switched off by the call that built
                            // it, which is why a correct azimuth drew nothing.
                            // ...and it is gated on the zoom threshold, not just on
                            // whether the bearing is switched on.
                            //
                            // addFovToMap ends in setVisible(), unconditionally, and
                            // update() calls this on every pass for a camera whose
                            // bearing is on. applyZoomGate() runs afterwards but only
                            // dispatches when visibility actually CHANGES, so once the
                            // marker was already hidden the gate was a no-op and each
                            // pan put the line back -- bearings floating over a map
                            // with no markers on it.
                            visible && isWithinZoom(),
                            // Labels on, range rings off.
                            //
                            // With bLabels true and rangeLines 0, ATAK writes the
                            // bearing along the line itself -- so the operator reads
                            // the azimuth off the map instead of opening the sensor
                            // pane to find it. The six-argument form we used before
                            // hard-codes these to false and 100, which is why the line
                            // was unlabeled and carried range rings nobody asked for.
                            //
                            // The azimuth is true north as it arrives: ALERT's pan was
                            // verified against view.line at a median of 0.00 degrees
                            // across 1,298 cameras, so nothing is corrected here.
                            true,
                            0.0);
            if (f == null) {
                Log.w(TAG, "ATAK declined to create a SensorFOV for " + c.id);
                return;
            }
            f.setStrokeWeight(still ? 2.0 : 5.0);
            f.setStrokeColor(still ? 0xFFFFFFFF : LINE_COLOR);
            f.setRangeLineStrokeWeight(1.0);
            f.setRangeLineStrokeColor(0xFF000000);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not style the sensor FOV for " + c.id, e);
        }
    }

    /**
     * Give the marker an icon whose direction is visible.
     *
     * <p>ATAK rotates a marker's icon for {@code STYLE_ROTATE_HEADING_MASK} — that part
     * was already working. It just could not be seen: the stock sensor icon for
     * {@code b-m-p-s-p-loc} is a symmetric dot, and rotating a circle looks like
     * nothing at all. The only visible effect was the heading arrow that style bit
     * draws, which is not what was wanted.
     *
     * <p>So the marker gets an asymmetric icon that points, drawn facing north, and
     * ATAK's rotation does the rest.
     */
    /**
     * Mark a stills-only camera with ATAK's own Quick Pic icon, and leave every other
     * camera exactly as ATAK draws it.
     *
     * <p>ATAK derives a marker's icon from its CoT type unless
     * {@code adapt_marker_icon} says otherwise, so a {@code b-m-p-s-p-loc} marker gets
     * {@code assets/icons/sensor_location.png} -- a camcorder. For a camera that
     * streams that is exactly right, and it is left alone.
     *
     * <p>A camera that only serves a still gets {@code b-i-x-i.png} instead: the
     * marker Quick Pic drops when it saves a photo, which in ATAK's own language means
     * "there is a picture here". Referenced through {@code asset:/}, which
     * {@code AssetProtocolHandler} resolves out of ATAK at runtime -- so it is the
     * real icon at the right size, not a copy, and nothing from the SDK enters this
     * repository.
     *
     * <p>Two earlier attempts at this were wrong and are worth not repeating. Custom
     * 96x96 artwork rendered at three times the size of every other marker on the map,
     * because ATAK's own marker icons are 32x32. And clearing
     * {@code adapt_marker_icon} for <em>all</em> cameras replaced the camcorder
     * everywhere with plain arrows, which is a downgrade: the camcorder already says
     * "video" perfectly well.
     */
    private void setDirectionalIcon(Marker m, Camera c) {
        // Streaming camera: ATAK's camcorder is right. Do not touch it.
        if (c.hasStream())
            return;
        try {
            if (stillIcon == null) {
                stillIcon = new Icon.Builder()
                        .setImageUri(Icon.STATE_DEFAULT, "asset:/icons/b-i-x-i.png")
                        .setAnchor(Icon.ANCHOR_CENTER, Icon.ANCHOR_CENTER)
                        .setColor(Icon.STATE_DEFAULT, 0xFFFFFFFF)
                        .build();
            }
            m.setMetaBoolean("adapt_marker_icon", false);
            m.setIcon(stillIcon);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not set the stills icon for " + c.id, e);
        }
    }

    private Icon icon(int drawable) {
        return new Icon.Builder()
                .setImageUri(Icon.STATE_DEFAULT, "android.resource://"
                        + pluginContext.getPackageName() + "/" + drawable)
                .setAnchor(Icon.ANCHOR_CENTER, Icon.ANCHOR_CENTER)
                .setColor(Icon.STATE_DEFAULT, 0xFFFFFFFF)
                .build();
    }


    /**
     * Turn the marker's icon to face where the camera is looking.
     *
     * <p>ATAK's stock sensor icon always faces one way, so a hundred cameras all look
     * like they are staring north. Joe's plugin rotated the icon with the azimuth and
     * that is the behavior to match: the marker itself carries the bearing, so
     * direction is readable at a glance without turning every FOV line on.
     *
     * <p>{@code setTrack(heading, speed)} plus {@code STYLE_ROTATE_HEADING_MASK} is
     * the mechanism — the same one a self-marker's {@code <track course=…/>} uses.
     * Smooth rotation is on so a PTZ camera slewing between refreshes turns rather
     * than snapping.
     */
    private void aimIcon(Marker m, Camera c) {
        // A camera with no reported bearing faces east, which is how ATAK lays its own
        // camera markers down. It is a convention rather than a claim about direction,
        // and it beats the alternatives: leaving them at the artwork's north reads as a
        // bearing that was measured, and pointing them at a placeholder pan aims 9,000
        // DOT cameras the same way at once.
        // No bearing: leave the track alone entirely. The east-facing artwork already
        // points where it should, and any rotation would move it off that.
        //
        // Stills cameras are left alone for the same reason: they wear a camera body,
        // which has no nose to aim. Rotating one just tips it over.
        if (!c.hasFov() || !c.hasStream())
            return;
        try {
            m.setTrack((c.pan + ICON_HEADING_OFFSET + 360) % 360, 0);
            // NOARROW as well as ROTATE_HEADING: the icon itself carries the
            // direction now, so ATAK's separate heading arrow is redundant clutter.
            //
            // NOT smooth rotation. That flag animates the icon towards its new
            // heading over time, while SensorFOV.setMetrics snaps the bearing line
            // there immediately -- so every move showed the line jump and the icon
            // glide after it, and which one appeared to lead depended on when you
            // looked. It was added so a slewing camera would turn rather than snap,
            // which only works if the line animates too. It does not, so the icon
            // snaps with it and the two stay together.
            m.setStyle((m.getStyle() & ~Marker.STYLE_SMOOTH_ROTATION_MASK)
                    | Marker.STYLE_ROTATE_HEADING_MASK
                    | Marker.STYLE_ROTATE_HEADING_NOARROW_MASK);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not aim the icon for " + c.id, e);
        }
    }

    private void update(Marker m, Camera c) {
        m.setTitle(c.label() + (c.offline ? " (offline)" : ""));
        m.setMetaString("callsign", c.label());
        // Offline cameras go grey; a camera that cannot see anything should not
        // read the same as one that can.
        //
        // Cameras with a bearing are the exception: for those the color doubles as
        // the "line is on" indicator, so a refresh must re-assert the SAME state
        // syncFov set. A refresh that colored one unconditionally would put the
        // orange back on a marker whose line the operator had just switched off.
        if (c.hasFov())
            applyTint(m, c, requested.contains(c.id));
        else
            m.setMetaInteger("color", baseColor(c));
        m.setMetaString("remarks", remarks(c));
        if (c.hasFov()) {
            // Sensor setup is done here, not only at creation.
            //
            // A marker is built the moment its camera is drawn, which is routinely
            // before the moving half of the catalog has arrived -- and with no pan,
            // c.hasFov() was false, so create() skipped attachSensor AND skipped
            // registering the hideFov listener. Nothing added them afterwards. The
            // marker then had no sensor attributes and no listener for the rest of
            // its life: ATAK's radial toggled a key nobody was watching, and the
            // bearing never appeared however many times it was pressed.
            //
            // Cheap to re-assert: guarded by a flag, so it runs once per marker.
            if (!m.hasMetaValue(SENSOR_READY)) {
                attachSensor(m, c);
                m.addOnMetadataChangedListener(
                        SensorDetailHandler.HIDE_FOV, fovWatch);
                m.setMetaBoolean(SENSOR_READY, true);
                if (requested.contains(c.id))
                    m.toggleMetaData(SensorDetailHandler.HIDE_FOV, false);
            }
            // Only the bearing moves between refreshes; everything else is fixed.
            m.setMetaInteger(SensorDetailHandler.AZIMUTH_ATTRIBUTE,
                    (int) Math.round(c.pan));
            aimIcon(m, c);              // follow the camera as it slews
            if (requested.contains(c.id))
                styleFov(m, c, true);
        }
    }

    private static String remarks(Camera c) {
        final StringBuilder b = new StringBuilder();
        b.append(c.provider);
        if (c.sponsor != null && !c.sponsor.isEmpty())
            b.append(" / ").append(c.sponsor);
        b.append('\n').append(c.offline ? "OFFLINE" : "online");
        if (!Double.isNaN(c.pan))
            b.append(String.format(java.util.Locale.US, "\npan %.1f°", c.pan));
        if (!Double.isNaN(c.fov))
            b.append(String.format(java.util.Locale.US, "  fov %.1f°", c.fov));
        if (!c.ptz)
            b.append("\nfixed camera");
        return b.toString();
    }

    // ---- the azimuth line (fov=0, alpha=0: a bearing, not a wedge) --------

    public void showFov(Camera c) {
        if (c == null || !c.hasFov())
            return;
        // Remember the request whether or not the camera is on the map yet.
        //
        // A marker only exists for what is currently drawn, so a camera in another
        // state, off screen, or below the zoom threshold had no marker and this
        // returned silently -- the button did nothing and said nothing. Searching is
        // global, so that is most of the catalog.
        //
        // Recording the intent first means create() leaves hideFov off and update()
        // styles the line the moment the marker is built, so "Go to" brings the
        // camera into view already showing its bearing.
        requested.add(c.id);
        showing.add(c.id);
        final Marker m = markers.get(c.id);
        if (m == null)
            return;
        m.setMetaInteger(SensorDetailHandler.AZIMUTH_ATTRIBUTE,
                (int) Math.round(c.pan));
        m.setMetaInteger(SensorDetailHandler.RANGE_ATTRIBUTE,
                (int) Math.round(Math.min(rangeMeters,
                        SensorDetailHandler.MAX_SENSOR_RANGE)));
        setFovVisible(m, true);
        styleFov(m, c, true);
    }

    /**
     * Take a bearing down, and do not depend on {@link #syncFov} to finish the job.
     *
     * <p>This used to write the {@code hideFov} metadata and stop, on the assumption
     * that {@code fovWatch} would arrive and switch the geometry off. It does -- but
     * {@code syncFov} early-returns when {@code shown} has no entry for the id, which
     * is any camera not in the currently drawn set. So "turn off all bearings" on a
     * camera that had scrolled out of view cleared the request and greyed the button
     * while leaving the line drawn on the map: the panel said there were no bearings
     * and the map plainly disagreed.
     *
     * <p>The metadata write stays, because ATAK's radial reads it and the two
     * switches must not disagree. The geometry is now switched off here as well.
     */
    public void hideFov(String id) {
        requested.remove(id);
        showing.remove(id);
        final Marker m = markers.get(id);
        if (m == null)
            return;
        setFovVisible(m, false);
        try {
            final com.atakmap.android.maps.SensorFOV f = existingFov(m);
            if (f != null)
                f.setVisible(false);
            final Camera c = shown.get(id);
            if (c != null)
                applyTint(m, c, false);     // the marker stops reading as "line on"
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not hide the sensor FOV for " + id, e);
        }
    }

    public void hideAllFovs() {
        // Over `requested`, so a bearing asked for on a camera that is currently off
        // screen is turned off too. Turning off only what happens to be drawn would
        // leave lines waiting to reappear.
        for (String id : new ArrayList<>(requested))
            hideFov(id);
    }

    /**
     * Show or hide the azimuth line through ATAK's own sensor machinery.
     *
     * <p>{@code hideFov} is the state the radial's own toggle reads and writes, so
     * driving it here keeps the panel's Play button and ATAK's button from disagreeing.
     * The broadcast is the same one {@code actions/toggle_sensor_fov.xml} sends —
     * using ATAK's path rather than a parallel one of our own.
     */
    private void setFovVisible(Marker m, boolean visible) {
        // Write the state and nothing else. fovWatch does the drawing.
        //
        // toggleMetaData is not setMetaBoolean:
        //
        //     if (on) setMetaBoolean(key, true); else removeMetaData(key);
        //
        // so PRESENCE of hideFov means hidden and ABSENCE means shown. ATAK tests it
        // with hasMetaValue -- key presence, never the value -- so writing
        // hideFov=false left it hidden as far as ATAK was concerned, while the radial
        // menu's selected='!{${hideFov}}' reads the VALUE and lit the button up.
        // Button on, line off, and neither one obviously wrong.
        m.toggleMetaData(SensorDetailHandler.HIDE_FOV, !visible);
        m.refresh(mapView.getMapEventDispatcher(), null, getClass());
    }

    /**
     * Draw or hide a marker's bearing line to match its {@code hideFov} state.
     *
     * <p>This is the only place the line is turned on or off, and that is the point.
     * There are two switches for it — this plugin's "Show bearing" button and ATAK's
     * own radial "Show FOV Overlay" — and they were being wired to two different
     * mechanisms. ATAK's button broadcasts to {@code SensorDetailsReceiver}, which
     * toggles {@code hideFov} and then calls {@code setVisible} on a
     * <em>pre-existing</em> {@code SensorFOV}. Under lazy creation there is no such
     * object yet, so ATAK's button changed the state and drew nothing — which is
     * exactly what "the FOV stopped working" looked like.
     *
     * <p>Hanging this off a metadata listener puts both switches on one path:
     * whoever writes {@code hideFov}, this runs, and the object is created on the
     * spot if it is needed. {@code removeMetaData} dispatches a change event just as
     * {@code setMetaBoolean} does, so both directions arrive here.
     *
     * <p>It also keeps the memory fix intact. Nothing is built for a camera whose
     * bearing has never been asked for, so a viewport full of fire cameras still
     * costs no GL geometry — the 141 MB that made lazy creation necessary in the
     * first place.
     */
    private void syncFov(MapItem item) {
        if (!(item instanceof Marker))
            return;
        final Marker m = (Marker) item;
        final String id = m.getUID().startsWith(UID_PREFIX)
                ? m.getUID().substring(UID_PREFIX.length()) : null;
        if (id == null)
            return;
        final Camera c = shown.get(id);
        if (c == null || !c.hasFov())
            return;
        if (m.hasMetaValue(SensorDetailHandler.HIDE_FOV)) {
            // An explicit turn-off, from ATAK's radial or from hideFov: clear the
            // request as well, or the line would come back on the next pan.
            requested.remove(id);
            showing.remove(id);
            final com.atakmap.android.maps.SensorFOV f = existingFov(m);
            if (f != null)
                f.setVisible(false);
            applyTint(m, c, false);
        } else {
            requested.add(id);
            showing.add(id);
            styleFov(m, c, true);
            applyTint(m, c, true);
        }
    }

    /** One listener for every marker: {@link #syncFov} works out which it is. */
    private final MapItem.OnMetadataChangedListener fovWatch =
            new MapItem.OnMetadataChangedListener() {
                @Override
                public void onMetadataChanged(MapItem item, String key) {
                    syncFov(item);
                }
            };

    /**
     * This marker's SensorFOV if one already exists, without creating one.
     *
     * <p>ATAK keys them by {@code <marker uid> + "-fov"} in a single "Field Of View"
     * group, which is how {@code SensorDetailsReceiver} finds the one to toggle.
     * Looking it up rather than calling {@code addFovToMap} matters for hiding: the
     * creating call would build a Shape and its GL geometry for a camera whose line
     * is being switched off.
     */
    private static com.atakmap.android.maps.SensorFOV existingFov(Marker m) {
        try {
            final MapGroup g = SensorDetailHandler.getOrAddMapGroup();
            if (g == null)
                return null;
            final MapItem i = g.findItem("uid",
                    m.getUID() + SensorDetailHandler.UID_POSTFIX);
            return i instanceof com.atakmap.android.maps.SensorFOV
                    ? (com.atakmap.android.maps.SensorFOV) i : null;
        } catch (LinkageError | RuntimeException e) {
            return null;
        }
    }

    /**
     * Cameras whose marker currently carries a drawn line.
     *
     * <p>Emptied for a camera when its marker goes away, because that is what it
     * describes: geometry that exists. {@link #setRangeMeters} walks it to re-aim
     * live lines, which is only meaningful for markers that are on the map.
     */
    private final Set<String> showing = new HashSet<>();

    /**
     * Cameras the operator has asked to see a bearing for.
     *
     * <p>Separate from {@link #showing} because the two are different facts and
     * conflating them cost the feature once. A request outlives its marker: a
     * bearing asked for on a camera that then scrolls out of the viewport is still
     * wanted, and the line comes back with the camera rather than being silently
     * dropped the moment you look somewhere else.
     *
     * <p>It also outlives a camera with no marker at all -- {@code showFov} records
     * a request for a camera in another state or below the zoom threshold, so
     * "Go to" arrives with the bearing already on.
     *
     * <p>Only three things clear a request: {@code hideFov}, {@code hideAllFovs},
     * and ATAK's own radial toggle arriving through {@link #syncFov}. Panning is not
     * one of them.
     */
    private final Set<String> requested = new HashSet<>();

    public boolean isFovShowing(String id) {
        return requested.contains(id);
    }

    /**
     * How many bearing lines are drawn right now.
     *
     * <p>The panel's "Bearings off" button counts with this and disables itself at
     * zero, so a control that would do nothing says so before it is pressed rather
     * than after. Bearings are turned on one camera at a time, from the camera's own
     * pane or from ATAK's radial, and there was no way to see how many were on --
     * let alone put them all away -- without visiting each camera again.
     */
    public int fovCount() {
        return requested.size();
    }

    public void setRangeMeters(double meters) {
        this.rangeMeters = meters;
        for (String id : new ArrayList<>(showing)) {
            final Marker m = markers.get(id);
            if (m != null) {
                m.setMetaDouble(SensorDetailHandler.RANGE_ATTRIBUTE,
                        Math.min(meters, SensorDetailHandler.MAX_SENSOR_RANGE));
                m.refresh(mapView.getMapEventDispatcher(), null, getClass());
            }
        }
    }

    public double getRangeMeters() {
        return rangeMeters;
    }

    /** Push a refreshed bearing into a line that is currently showing. */
    public void updateFov(Camera c) {
        if (c == null || !requested.contains(c.id) || !c.hasFov())
            return;
        final Marker m = markers.get(c.id);
        if (m == null)
            return;
        m.setMetaInteger(SensorDetailHandler.AZIMUTH_ATTRIBUTE,
                (int) Math.round(c.pan));
        styleFov(m, c, true);
        m.refresh(mapView.getMapEventDispatcher(), null, getClass());
    }

    // ---- navigation -------------------------------------------------------

    /**
     * Fly to a camera and land at a zoom where it is actually drawn.
     *
     * <p>Centring alone was not enough: if the map was further out than the render
     * threshold, "Go to" took you to the right place and showed you nothing, which
     * reads as the button being broken. So it zooms as well as pans, to a resolution
     * comfortably inside the threshold rather than exactly at it — sitting on the
     * boundary means one pinch makes the camera vanish again.
     */
    public void goTo(Camera c) {
        if (c == null)
            return;
        final GeoPoint p = new GeoPoint(c.lat, c.lon);
        try {
            final double limit = maxResolution == Double.MAX_VALUE
                    ? GOTO_RESOLUTION
                    : Math.min(GOTO_RESOLUTION, maxResolution * 0.5);
            // Never zoom the operator back OUT to get to a camera. If they are
            // already closer in than the arrival altitude, Go to is a pan and
            // nothing else; pulling back to a fixed altitude would throw away the
            // view they had deliberately set up.
            final double target = Math.min(mapView.getMapResolution(), limit);
            mapView.getMapController().panZoomTo(
                    p, mapView.mapResolutionAsMapScale(target), true);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "panZoomTo failed; falling back to a plain pan", e);
            mapView.getMapController().panTo(p, true);
        }
    }

}
