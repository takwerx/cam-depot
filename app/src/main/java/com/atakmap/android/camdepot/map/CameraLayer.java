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

    /** Azimuth-line colour: the same orange as the operator's own sensor marker. */
    private static final int LINE_COLOR = 0xFFFF7700;

    /**
     * Resolution to fly to on "Go to", in metres per pixel — close enough to see the
     * camera and its surroundings without being on top of it.
     */
    private static final double GOTO_RESOLUTION = 30;

    /**
     * Line length in metres until the operator changes it.
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
    private static final int BATCH = 120;

    private final MapView mapView;
    private final android.content.Context pluginContext;
    private final MapGroup group;
    /** Built once: the same directional icon is shared by every marker. */
    private Icon dirIcon;
    /** Artwork for cameras with no bearing: already facing east, never rotated. */
    private Icon eastIcon;

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
     * Cameras draw at or below this resolution, in metres per pixel. Larger means
     * more zoomed out, so this is a "no further out than" limit.
     *
     * <p>Default is roughly county scale — far enough out to be useful, close enough
     * that a whole-state view is not covered in icons. The operator resets it by
     * zooming to where they want cameras to start and pressing "Use this zoom",
     * which avoids them having to reason about scale at all.
     */
    private double maxResolution = 500;
    private double rangeMetres = DEFAULT_RANGE_M;
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
    }

    public void dispose() {
        main.removeCallbacks(gateTick);
        main.removeCallbacks(addTick);
        main.removeCallbacks(removeTick);
        mapView.removeOnMapMovedListener(moved);
        clear();
        mapView.getRootGroup().removeGroup(group);
    }

    // ---- zoom gating ------------------------------------------------------

    /** @param metresPerPixel cameras draw at or below this; larger means zoomed out */
    public void setMaxResolution(double metresPerPixel) {
        this.maxResolution = metresPerPixel;
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
                main.post(this);
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
            // One registration call for the whole batch, after the markers exist.
            flushVideoEntries();
            if (!pending.isEmpty())
                main.post(this);
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

    private void remove(String id) {
        // Deliberately NOT hideFov(id): ATAK's own OnGroupChangedListener drops the
        // SensorFOV when the marker leaves the group, so writing hideFov here only
        // buys a metadata dispatch and a group scan per removal -- during viewport
        // churn that is 120 of them a frame, for a marker that is going away.
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
            // protocol=raw with the whole URL as the address, port -1.
            //
            // This is the shape from the operator's working CoT, and it is confirmed
            // playing on device. It was briefly "corrected" to let ATAK infer the
            // protocol from the URL, on the theory that raw suited MJPEG and HLS
            // needed HTTPS. That broke playback outright. The theory was wrong; raw
            // is right for these too. Do not change it again without a camera that
            // demonstrably fails under raw and plays under something else.
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

            // Queued, not registered here. See flushVideoEntries().
            pendingEntries.add(ce);
            videoUids.put(c.id, uid);
            m.setMetaString("videoUID", uid);
            m.setMetaString("videoUrl", c.stream);
        } catch (LinkageError | RuntimeException e) {
            // Video is a bonus; a plugin must not fail to draw a camera over it.
            Log.w(TAG, "could not attach video for " + c.id, e);
        }
    }

    /**
     * Colour a marker to say whether its bearing line is on, and repaint it.
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
     * <p>The plain metadata write does not refresh, so the colour sat in the metadata
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
            final Class<?> proto = Class.forName(
                    "gov.tak.api.video.ConnectionEntryBase$Protocol");
            final Object raw = proto.getField("RAW").get(null);
            gov.tak.api.video.ConnectionEntry.class
                    .getMethod("setProtocol", proto).invoke(ce, raw);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "could not force RAW protocol; leaving " + ce.getProtocol(), e);
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
        // FOV controls they cannot use -- but ATAK does not recognise that type: the
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
                (int) Math.round(Math.min(rangeMetres,
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
        m.setMetaInteger(SensorDetailHandler.RANGE_LINES_ATTRIBUTE, 25);
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
        try {
            final com.atakmap.android.maps.SensorFOV f =
                    SensorDetailHandler.addFovToMap(m,
                            c.pan,
                            0,                              // no wedge: a bearing line
                            Math.min(rangeMetres, SensorDetailHandler.MAX_SENSOR_RANGE),
                            new float[] { 1f, 1f, 1f, 0f }, // alpha 0, per the CoT
                            // The sixth argument is VISIBLE, not labels.
                            //
                            // It was read as "labels" and hard-coded false, so every
                            // call -- create, show, and every refresh -- ended in
                            // addFovToMap's own setVisible(false). The line was built
                            // correctly and then switched off by the call that built
                            // it, which is why a correct azimuth drew nothing.
                            visible);
            if (f == null) {
                Log.w(TAG, "ATAK declined to create a SensorFOV for " + c.id);
                return;
            }
            f.setStrokeWeight(5.0);
            f.setStrokeColor(LINE_COLOR);
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
    private void setDirectionalIcon(Marker m, Camera c) {
        try {
            // Two pieces of artwork, so rotation is only ever used where a real
            // bearing justifies it:
            //
            //   ic_cam_dir   drawn facing north, rotated to the camera's bearing
            //   ic_cam_east  drawn facing east, never rotated
            //
            // The second exists because chasing the sign convention for a fixed
            // direction was a guessing game -- track 0 rendered north, track 90
            // rendered south, which no single linear rotation explains. Drawing the
            // convention into the artwork and leaving the track alone removes the
            // question rather than answering it.
            if (dirIcon == null)
                dirIcon = icon(com.atakmap.android.camdepot.plugin.R.drawable.ic_cam_dir);
            if (eastIcon == null)
                eastIcon = icon(com.atakmap.android.camdepot.plugin.R.drawable.ic_cam_east);
            m.setIcon(c.hasFov() ? dirIcon : eastIcon);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "could not set the directional icon for " + c.id, e);
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
     * that is the behaviour to match: the marker itself carries the bearing, so
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
        if (!c.hasFov())
            return;
        try {
            m.setTrack((c.pan + ICON_HEADING_OFFSET + 360) % 360, 0);
            // NOARROW as well as ROTATE_HEADING: the icon itself carries the
            // direction now, so ATAK's separate heading arrow is redundant clutter.
            m.setStyle(m.getStyle()
                    | Marker.STYLE_ROTATE_HEADING_MASK
                    | Marker.STYLE_ROTATE_HEADING_NOARROW_MASK
                    | Marker.STYLE_SMOOTH_ROTATION_MASK);
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
        // Cameras with a bearing are the exception: for those the colour doubles as
        // the "line is on" indicator, so a refresh must re-assert the SAME state
        // syncFov set. A refresh that coloured one unconditionally would put the
        // orange back on a marker whose line the operator had just switched off.
        if (c.hasFov())
            applyTint(m, c, showing.contains(c.id));
        else
            m.setMetaInteger("color", baseColor(c));
        m.setMetaString("remarks", remarks(c));
        if (c.hasFov()) {
            // Only the bearing moves between refreshes; everything else is fixed.
            m.setMetaInteger(SensorDetailHandler.AZIMUTH_ATTRIBUTE,
                    (int) Math.round(c.pan));
            aimIcon(m, c);              // follow the camera as it slews
            if (showing.contains(c.id))
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
        final Marker m = markers.get(c.id);
        if (m == null)
            return;
        m.setMetaInteger(SensorDetailHandler.AZIMUTH_ATTRIBUTE,
                (int) Math.round(c.pan));
        m.setMetaInteger(SensorDetailHandler.RANGE_ATTRIBUTE,
                (int) Math.round(Math.min(rangeMetres,
                        SensorDetailHandler.MAX_SENSOR_RANGE)));
        setFovVisible(m, true);     // fovWatch draws it
    }

    public void hideFov(String id) {
        final Marker m = markers.get(id);
        if (m != null)
            setFovVisible(m, false);    // fovWatch hides it
        showing.remove(id);
    }

    public void hideAllFovs() {
        for (String id : new ArrayList<>(showing))
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
            showing.remove(id);
            final com.atakmap.android.maps.SensorFOV f = existingFov(m);
            if (f != null)
                f.setVisible(false);
            applyTint(m, c, false);
        } else {
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

    /** Cameras whose azimuth line is currently asked for. */
    private final Set<String> showing = new HashSet<>();

    public boolean isFovShowing(String id) {
        return showing.contains(id);
    }

    public void setRangeMetres(double metres) {
        this.rangeMetres = metres;
        for (String id : new ArrayList<>(showing)) {
            final Marker m = markers.get(id);
            if (m != null) {
                m.setMetaDouble(SensorDetailHandler.RANGE_ATTRIBUTE,
                        Math.min(metres, SensorDetailHandler.MAX_SENSOR_RANGE));
                m.refresh(mapView.getMapEventDispatcher(), null, getClass());
            }
        }
    }

    public double getRangeMetres() {
        return rangeMetres;
    }

    /** Push a refreshed bearing into a line that is currently showing. */
    public void updateFov(Camera c) {
        if (c == null || !showing.contains(c.id) || !c.hasFov())
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
            mapView.getMapController().panZoomTo(
                    p, mapView.mapResolutionAsMapScale(limit), true);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "panZoomTo failed; falling back to a plain pan", e);
            mapView.getMapController().panTo(p, true);
        }
    }

}
