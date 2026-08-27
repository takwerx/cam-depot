package com.atakmap.android.camdepot.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.camdepot.data.CameraStore;
import com.atakmap.android.cot.detail.SensorDetailHandler;
import com.atakmap.android.camdepot.data.ScaleBar;
import com.atakmap.android.camdepot.data.Units;
import com.atakmap.android.camdepot.map.CameraLayer;
import com.atakmap.android.camdepot.model.Camera;
import com.atakmap.android.camdepot.model.Catalog;
import com.atakmap.android.camdepot.net.Http;
import com.atakmap.android.camdepot.plugin.R;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The side panel. It is the primary control: the map shows exactly what the filters
 * here select, never the whole catalog.
 *
 * <p>Anonymous inner classes rather than lambdas throughout — the SDK documents
 * lambdas breaking under release proguard, and this ships in release builds.
 *
 * <p>Every dialog and toast is built on the <em>MapView</em> context, not the plugin
 * context. A dialog on the plugin context throws {@code BadTokenException} and takes
 * ATAK down with it.
 */
public final class CamDepotPane implements CameraStore.Listener {

    private static final String TAG = "CamDepotPane";

    /** How often the dynamic shard is re-read. Upstream moves on ~15s; this is polite. */
    private static final long REFRESH_MS = 60_000;

    private final Context pluginContext;
    private final MapView mapView;
    private final CameraStore store;
    private final CameraLayer layer;
    private final View root;

    private final TextView status;
    /**
     * Buttons, not Spinners. A Spinner's dropdown is an {@link android.app.Dialog}
     * built from the context its view was inflated with — the plugin context, which
     * has no window token — so opening one throws {@code BadTokenException} and takes
     * ATAK down. Confirmed on device 2026-08-25 at Spinner$DialogPopup.show.
     * A button that opens an AlertDialog on the MapView context is the safe path.
     */
    private final Button stateButton, providerButton, countyButton;
    private final EditText search;
    private final CheckBox fireOnly, liveOnly, stillOnly;
    private final SeekBar radius;
    private final TextView radiusLabel, zoomLabel;
    private final ListView list;
    /** Holds the filter controls; they are a header view, not part of the pane. */
    private final View controls;
    private final CameraAdapter adapter;

    private String currentState = "CA";
    private final List<String> stateCodes = new ArrayList<>();
    private final List<String> providerNames = new ArrayList<>();
    private final List<String> countyNames = new ArrayList<>();
    /** null means "all". */
    private String selectedProvider, selectedCounty;
    /** Radius in the operator's own unit (miles / km / NM). 0 means no filter. */
    private double radiusBig = 0;
    private GeoPoint centre;                  // null means "use my location"

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            if (store.isLoaded(currentState))
                store.refreshState(currentState);
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    /**
     * Keeps the zoom readout honest as the operator moves the map.
     *
     * <p><strong>Runs on the GL render thread.</strong> ATAK dispatches this from
     * {@code GLMapView.dispatchCameraChanged} over JNI, so touching a View here is a
     * native SIGSEGV with no Java stack trace — it killed ATAK twice on 2026-08-25.
     * Post to the main looper, and coalesce: during a pinch it fires every frame.
     */
    private final com.atakmap.map.AtakMapView.OnMapMovedListener zoomWatch =
            new com.atakmap.map.AtakMapView.OnMapMovedListener() {
                @Override
                public void onMapMoved(com.atakmap.map.AtakMapView v, boolean animate) {
                    handler.removeCallbacks(labelTick);
                    handler.postDelayed(labelTick, 150);
                }
            };

    /**
     * Map-move follow-up. Deliberately does <em>not</em> re-run {@link #apply()}: the
     * filters have not changed, only the viewport, and re-filtering and re-sorting
     * thousands of cameras on every pan is work for nothing. The layer already redraws
     * itself from the new viewport.
     */
    private final Runnable labelTick = new Runnable() {
        @Override
        public void run() {
            updateZoomLabel();
            updateStatus();
        }
    };

    public CamDepotPane(Context pluginContext, MapView mapView, String baseUrl) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.layer = new CameraLayer(mapView, pluginContext);
        this.store = new CameraStore(baseUrl, this);

        root = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
        status = root.findViewById(R.id.status);
        list = root.findViewById(R.id.cameras);
        legend(root.findViewById(R.id.legend));

        // The filters ride as a header of the list so the panel has exactly one
        // scroller. See the comment in controls_header.xml.
        controls = PluginLayoutInflater.inflate(pluginContext,
                R.layout.controls_header, null);
        list.addHeaderView(controls, null, false);
        stateButton = controls.findViewById(R.id.state);
        providerButton = controls.findViewById(R.id.provider);
        countyButton = controls.findViewById(R.id.county);
        search = controls.findViewById(R.id.search);
        fireOnly = controls.findViewById(R.id.fire_only);
        liveOnly = controls.findViewById(R.id.live_only);
        stillOnly = controls.findViewById(R.id.still_only);
        radius = controls.findViewById(R.id.radius);
        radiusLabel = controls.findViewById(R.id.radius_label);
        zoomLabel = controls.findViewById(R.id.zoom_label);

        // A ListView blocks focus to its children by default, which means an EditText
        // living in a header view never receives a keystroke -- the search box looked
        // present and did nothing. These two lines are what let a focusable widget
        // inside the list actually be typed into.
        list.setItemsCanFocus(true);
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        adapter = new CameraAdapter();
        list.setAdapter(adapter);

        wire();
        setRadiusFromProgress(0);
        mapView.addOnMapMovedListener(zoomWatch);
        layer.setMaxResolution(savedThreshold());
        updateZoomLabel();
        store.loadCatalog();
        handler.postDelayed(refreshTick, REFRESH_MS);
    }

    public View getView() {
        return root;
    }

    public void dispose() {
        handler.removeCallbacks(refreshTick);
        handler.removeCallbacks(labelTick);
        mapView.removeOnMapMovedListener(zoomWatch);
        layer.dispose();
    }

    // ---- wiring -----------------------------------------------------------

    /**
     * Somewhere to put the camera detail so the map stays usable behind it.
     *
     * <p>The detail used to be an AlertDialog, which blocks the map: you could look at
     * the picture or move the map, not both. ATAK's own KML feature details open in a
     * side pane for exactly this reason — the operator wants to scroll around with the
     * image still up. The plugin supplies the host because {@code PaneBuilder} and the
     * UI service live there.
     */
    public interface DetailHost {
        void showDetailPane(android.view.View v);
        void hideDetailPane();
    }

    private DetailHost detailHost;

    public void setDetailHost(DetailHost h) {
        this.detailHost = h;
    }

    private void wire() {
        // Tapping a stills camera on the map opens the same picture the panel shows.
        layer.setOnStillTapped(new CameraLayer.OnStillTapped() {
            @Override
            public void onStillTapped(final Camera c) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        showDetail(c);
                    }
                });
            }
        });

        final TextWatcher tw = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (search.getText().length() > 0 && !loadedAll) {
                    loadedAll = true;
                    status.setText("Loading every state so search covers all of them…");
                    store.loadAllStates(new Runnable() {
                        @Override
                        public void run() {
                            apply();
                        }
                    });
                }
                apply();
            }
        };
        search.addTextChangedListener(tw);

        final CompoundButton.OnCheckedChangeListener cc =
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        apply();
                    }
                };
        fireOnly.setOnCheckedChangeListener(cc);
        liveOnly.setOnCheckedChangeListener(cc);
        stillOnly.setOnCheckedChangeListener(cc);

        stateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose("State", stateCodes, currentState, new Chosen() {
                    @Override
                    public void onChosen(String value) {
                        if (value.equals(currentState) && store.isLoaded(value))
                            return;
                        currentState = value;
                        selectedCounty = null;
                        layer.clear();
                        stateButton.setText(value);
                        countyButton.setText("All counties");
                        status.setText(String.format(Locale.US, "Loading %s…", value));
                        store.loadState(value);
                    }
                });
            }
        });

        providerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose("Provider", providerNames, selectedProvider, new Chosen() {
                    @Override
                    public void onChosen(String value) {
                        // Strip the count back off before it becomes a filter.
                        //
                        // The list shows "CALTRANS (2917)" so a filter states what it
                        // will cost before it is used, but the camera's provider is
                        // just "CALTRANS". Storing the label made every provider
                        // filter match nothing, and the map went empty until the next
                        // refresh quietly reset it -- self-healing, and so easy to
                        // miss that it survived until an operator sat and watched it.
                        selectedProvider = ALL.equals(value) ? null : stripCount(value);
                        providerButton.setText(value);
                        apply();
                    }
                });
            }
        });

        countyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose("County", countyNames, selectedCounty, new Chosen() {
                    @Override
                    public void onChosen(String value) {
                        selectedCounty = ALL_COUNTIES.equals(value) ? null : value;
                        countyButton.setText(value);
                        apply();
                    }
                });
            }
        });

        radius.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean user) {
                setRadiusFromProgress(progress);
                if (user)
                    apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });

        controls.findViewById(R.id.zoom_set).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // Whatever the operator is looking at right now becomes the
                        // threshold. No scale to interpret: they set it by example.
                        setThreshold(mapView.getMapResolution());
                    }
                });

        controls.findViewById(R.id.zoom_preset).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        final List<String> names = new ArrayList<>();
                        for (int i = 0; i < PRESET_BAR_BIG.length; i++)
                            names.add(presetLabel(i));
                        names.add(ALWAYS);
                        choose("Draw cameras when the scale bar reads", names, null,
                                new Chosen() {
                                    @Override
                                    public void onChosen(String value) {
                                        if (ALWAYS.equals(value)) {
                                            setThreshold(Double.MAX_VALUE);
                                            return;
                                        }
                                        for (int i = 0; i < PRESET_BAR_BIG.length; i++) {
                                            if (presetLabel(i).equals(value)) {
                                                setThreshold(presetMetres(i)
                                                        / scaleBarPixels());
                                                return;
                                            }
                                        }
                                    }
                                });
                    }
                });

        controls.findViewById(R.id.pick_point).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Centre on where the map is looking. Simple, predictable, and it
                // works whether or not the device has a GPS fix.
                centre = mapView.getPoint().get();
                toast("Radius now measured from the map centre");
                apply();
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                // Position includes the header view, so shift back into the adapter.
                final int i = pos - list.getHeaderViewsCount();
                if (i >= 0 && i < adapter.getCount())
                    showDetail(adapter.getItem(i));
            }
        });
    }

    /**
     * Quote the scale bar, because it is already on screen.
     *
     * <p>Earlier versions of this readout invented their own vocabulary — metres per
     * pixel, then named bands like "county level" — and both made the operator
     * translate between the panel and the map. The bar in the lower left is the
     * reference they already have, so the threshold is expressed in the same terms.
     */
    static final String ALWAYS = "Always draw them";
    static final String PREF_ZOOM = "camdepot_zoom_threshold";

    /**
     * Starting points, expressed as what the scale bar would read — the same
     * vocabulary the readout uses. Presets exist so the plugin is useful before the
     * operator has tuned anything; "Use this zoom" is what they reach for once they
     * know what they want, and it overrides any preset.
     */
    /**
     * Preset scale-bar distances, in the operator's <em>own</em> large unit rather
     * than in metres.
     *
     * <p>Round metres are not round miles: a 1600 m preset labels itself "1.0 mi",
     * and 400 m becomes "0.2 mi". Defining them in the display unit keeps every entry
     * a clean number in whatever system ATAK is set to.
     */
    private static final double[] PRESET_BAR_BIG = {
            0.25, 1, 5, 15, 50
    };

    /**
     * What each preset is <em>for</em>, in the operator's terms. The distance alone
     * is a number to be decoded; the descriptor is what makes the list scannable.
     */
    private static final String[] PRESET_NAMES = {
            "city block", "neighborhood", "town", "county", "region"
    };

    private static double presetMetres(int i) {
        return Units.bigToMetres(PRESET_BAR_BIG[i]);
    }

    private static String presetLabel(int i) {
        final double n = PRESET_BAR_BIG[i];
        final String num = n == Math.floor(n)
                ? String.format(Locale.US, "%.0f", n)
                : String.format(Locale.US, "%.2f", n);
        return num + " " + Units.bigLabel() + "  —  " + PRESET_NAMES[i];
    }

    /** Set the threshold, remember it, and redraw. The one path all controls use. */
    private void setThreshold(double metresPerPixel) {
        layer.setMaxResolution(metresPerPixel);
        try {
            android.preference.PreferenceManager
                    .getDefaultSharedPreferences(mapView.getContext())
                    .edit().putFloat(PREF_ZOOM, (float) metresPerPixel).apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "could not remember the zoom threshold", e);
        }
        updateZoomLabel();
        apply();
    }

    /** Restore the operator's threshold, or a sensible default on first run. */
    private double savedThreshold() {
        try {
            return android.preference.PreferenceManager
                    .getDefaultSharedPreferences(mapView.getContext())
                    .getFloat(PREF_ZOOM, 500f);
        } catch (RuntimeException e) {
            return 500;
        }
    }

    /**
     * The marker colours, spelled out. They were previously discoverable only by
     * asking, which is the definition of a legend that does not exist. Pinned above
     * the list rather than inside the scrolling filter block, so it is readable
     * whenever a marker is.
     */
    private static void legend(TextView v) {
        final android.text.SpannableStringBuilder b =
                new android.text.SpannableStringBuilder();
        swatch(b, 0xFFFF6600, "Fire");
        b.append("   ");
        swatch(b, 0xFF33B5E5, "DOT / FAA");
        v.setText(b);
    }

    private static void swatch(android.text.SpannableStringBuilder b, int colour,
            String label) {
        final int at = b.length();
        b.append("\u25cf");
        b.setSpan(new android.text.style.ForegroundColorSpan(colour), at, b.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append(" ").append(label);
    }

    private void updateZoomLabel() {
        final String bar = ScaleBar.text(mapView);
        final double limit = layer.getMaxResolution();
        if (limit == Double.MAX_VALUE) {
            zoomLabel.setText("Always drawn  ·  scale bar: " + bar);
            return;
        }
        final String at = ScaleBar.describe(limit * scaleBarPixels());
        zoomLabel.setText(String.format(Locale.US,
                "Drawn at %s or closer  ·  scale bar now: %s%s",
                at, bar,
                mapView.getMapResolution() <= limit ? "" : "  — hidden"));
    }

    /** Pixels the scale bar spans, derived so the quoted threshold matches its text. */
    private double scaleBarPixels() {
        final double res = mapView.getMapResolution();
        if (res <= 0)
            return 200;
        final double m = ScaleBar.metres(mapView);
        return m > 0 ? m / res : 200;
    }

    private void setRadiusFromProgress(int progress) {
        radiusBig = progress * 2;               // 0..400 in the operator's unit
        radiusLabel.setText(radiusBig <= 0
                ? "Radius: off — the whole state"
                : String.format(Locale.US, "Within %.0f %s of %s", radiusBig,
                        Units.bigLabel(),
                        centre == null ? "my location" : "the map centre"));
    }

    // ---- store callbacks --------------------------------------------------

    @Override
    public void onCatalog(Catalog catalog) {
        stateCodes.clear();
        for (Catalog.State st : catalog.states)
            stateCodes.add(st.code);


        stateButton.setText(currentState);
        providerButton.setText(ALL);
        countyButton.setText(ALL_COUNTIES);

        status.setText(String.format(Locale.US, "%,d cameras, %d states — %s",
                catalog.totalCameras, catalog.states.size(), catalog.generated));

        store.loadState(currentState);
    }

    @Override
    public void onCameras(String state, List<Camera> cameras) {
        if (!state.equals(currentState))
            return;
        // Label the filters with what they would cost, so their effect is visible
        // before they are used rather than inferred from a changing total.
        // Providers are rebuilt from the cameras actually loaded, so Utah does not
        // offer Caltrans. A global list invites a filter that can only ever return
        // nothing, which reads as a broken plugin rather than an empty intersection.
        final java.util.TreeMap<String, Integer> present = new java.util.TreeMap<>();
        for (Camera c : cameras) {
            final Integer n = present.get(c.provider);
            present.put(c.provider, n == null ? 1 : n + 1);
        }
        providerNames.clear();
        providerNames.add(ALL);
        for (java.util.Map.Entry<String, Integer> e : present.entrySet())
            providerNames.add(e.getKey() + " (" + e.getValue() + ")");
        if (selectedProvider != null && !present.containsKey(selectedProvider)) {
            selectedProvider = null;
            providerButton.setText(ALL);
        }

        int fire = 0, streams = 0, stills = 0;
        for (Camera c : cameras) {
            if (c.offline)
                continue;               // down cameras are not shown at all
            if (c.fire)
                fire++;
            if (c.hasStream())
                streams++;
            else
                stills++;
        }
        liveOnly.setText(String.format(Locale.US, "Video (%,d)", streams));
        liveOnly.setEnabled(streams > 0);
        stillOnly.setText(String.format(Locale.US, "Still (%,d)", stills));
        fireOnly.setText(String.format(Locale.US, "Fire (%,d)", fire));
        final Catalog.State s = store.catalog() == null ? null
                : store.catalog().state(state);
        countyNames.clear();
        countyNames.add(ALL_COUNTIES);
        if (s != null)
            countyNames.addAll(s.counties);
        if (selectedCounty != null && !countyNames.contains(selectedCounty)) {
            selectedCounty = null;              // that county is not in this state
            countyButton.setText(ALL_COUNTIES);
        }
        apply();
    }

    @Override
    public void onError(String message) {
        status.setText(message);
        Log.w(TAG, message);
    }

    // ---- filtering --------------------------------------------------------

    /** Run every filter and push the result to both the list and the map. */
    private void apply() {
        final String query = search.getText().toString().trim();
        // A name search looks at every state, not just the loaded one. Typing "Moses"
        // while on California found nothing because Moses is in Nevada and Washington,
        // and no one types a camera name expecting it to only search where they happen
        // to be standing. Without a query, behaviour is unchanged: the selected state.
        final List<Camera> all = query.isEmpty()
                ? store.cameras(currentState)
                : store.everywhere();
        final String q = query.toLowerCase(Locale.US);
        final String provider = selectedProvider;
        final String county = selectedCounty;

        final boolean fire = fireOnly.isChecked();
        final boolean live = liveOnly.isChecked();
        final boolean still = stillOnly.isChecked();

        GeoPoint from = centre;
        final double radiusMetres = radiusBig > 0 ? Units.bigToMetres(radiusBig) : 0;
        if (from == null) {
            final com.atakmap.android.maps.Marker self = mapView.getSelfMarker();
            if (self != null && self.getPoint() != null)
                from = self.getPoint();
            else
                from = mapView.getPoint().get();
        }

        final List<Camera> out = new ArrayList<>();
        for (Camera c : all) {
            // A camera that is down has nothing to show, so it is never drawn and
            // never listed. It was a filter; that only ever produced a map full of
            // markers with no picture behind them.
            if (c.offline)
                continue;
            // Video / Still / Fire are INCLUSIVE: ticking more shows more. They were
            // written as three independent requirements, which made Video and Still
            // contradictory -- a camera cannot both have and not have a stream, so
            // ticking both returned nothing at all. Ticking none means no restriction.
            if (live || still || fire) {
                boolean keep = false;
                if (live && c.hasStream())
                    keep = true;
                if (still && !c.hasStream())
                    keep = true;
                if (fire && c.fire)
                    keep = true;
                if (!keep)
                    continue;
            }
            // County belongs to a state; applying the current state's county to a
            // cross-state search result would drop every hit from elsewhere.
            if (provider != null && !provider.equals(c.provider))
                continue;
            if (q.isEmpty() && county != null && !county.equalsIgnoreCase(c.county))
                continue;
            if (!q.isEmpty() && !c.label().toLowerCase(Locale.US).contains(q)
                    && !c.id.contains(q))
                continue;
            if (radiusMetres > 0 && from != null
                    && c.metresFrom(from.getLatitude(), from.getLongitude())
                            > radiusMetres)
                continue;
            out.add(c);
        }

        final GeoPoint sortFrom = from;
        if (sortFrom != null) {
            Collections.sort(out, new Comparator<Camera>() {
                @Override
                public int compare(Camera a, Camera b) {
                    return Double.compare(
                            a.metresFrom(sortFrom.getLatitude(), sortFrom.getLongitude()),
                            b.metresFrom(sortFrom.getLatitude(), sortFrom.getLongitude()));
                }
            });
        } else {
            Collections.sort(out, new Comparator<Camera>() {
                @Override
                public int compare(Camera a, Camera b) {
                    return a.label().compareToIgnoreCase(b.label());
                }
            });
        }

        adapter.setItems(out, sortFrom);

        // The layer takes the whole selection and decides what to draw from the
        // current viewport, so zooming in reveals cameras rather than keeping a set
        // chosen when the map was somewhere else.
        layer.show(new ArrayList<>(out));

        lastMatched = out.size();
        lastTotal = all.size();
        updateStatus();
    }

    private int lastMatched, lastTotal;
    private boolean loadedAll;

    private void updateStatus() {
        // Three separate facts, and cramming them into one sentence made the panel
        // unreadable: how many cameras exist, how many the filters kept, and how many
        // of those actually got drawn. One line each, in that order.
        final StringBuilder msg = new StringBuilder();
        msg.append(String.format(Locale.US, "%,d of %,d %s cameras up and matching",
                lastMatched, lastTotal, currentState));
        if (!layer.isWithinZoom())
            msg.append("\nMap: none drawn — zoom in past your threshold");
        else if (layer.getOmitted() > 0)
            msg.append(String.format(Locale.US,
                    "\nMap: too many on screen — %,d not drawn, zoom in",
                    layer.getOmitted()));
        else
            msg.append("\nMap: everything on screen is drawn");
        status.setText(msg.toString());
    }

    /** "CALTRANS (2917)" -> "CALTRANS". Labels carry counts; filters must not. */
    private static String stripCount(String label) {
        if (label == null)
            return null;
        final int i = label.lastIndexOf(" (");
        return i > 0 && label.endsWith(")") ? label.substring(0, i) : label;
    }

    static final String ALL = "All providers";
    static final String ALL_COUNTIES = "All counties";

    private interface Chosen {
        void onChosen(String value);
    }

    /**
     * A single-choice list on the <em>MapView</em> context.
     *
     * <p>This exists because a Spinner cannot be used here at all: its dropdown is a
     * Dialog created from the plugin context, which has no window token, and it
     * crashes ATAK outright. See the field comment on {@link #stateButton}.
     */
    private void choose(String title, final List<String> items, String current,
            final Chosen cb) {
        if (items.isEmpty()) {
            toast("Nothing to choose from yet");
            return;
        }
        final String[] arr = items.toArray(new String[0]);
        int checked = -1;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(current)) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(mapView.getContext())
                .setTitle(title)
                .setSingleChoiceItems(arr, checked,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                d.dismiss();
                                cb.onChosen(arr[w]);
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- camera detail ----------------------------------------------------

    private void showDetail(final Camera c) {
        if (c == null)
            return;
        // MapView context, not plugin context — see the class comment.
        final Context ui = mapView.getContext();
        final View v = PluginLayoutInflater.inflate(pluginContext,
                R.layout.camera_detail, null);
        final TextView info = v.findViewById(R.id.info);
        final ImageView image = v.findViewById(R.id.image);
        final Button play = v.findViewById(R.id.play);
        final Button refresh = v.findViewById(R.id.refresh);
        final SeekBar range = v.findViewById(R.id.range);
        final TextView rangeLabel = v.findViewById(R.id.range_label);

        info.setText(describe(c));

        // A stills camera shows its bearing for as long as its picture is open.
        //
        // Cameras that stream wear a directional arrow and carry their aim in the
        // icon. A stills camera cannot: it is an oblique fixed camera wearing a
        // camera body, and a camera body has no nose to point. So the direction is
        // answered the moment the operator actually wants it -- while they are
        // looking at the picture and asking "what am I looking at?" -- and then gets
        // out of the way again. 1,595 Caltrans cameras would otherwise be permanent
        // lines across the map, which is the soup the whole fov=0 design avoids.
        //
        // Only what this opened is closed again: a bearing the operator had already
        // switched on, here or from ATAK's radial, is left exactly as they left it.
        final boolean autoFov = !c.hasStream() && c.hasFov()
                && !layer.isFovShowing(c.id);
        if (autoFov)
            layer.showFov(c);

        play.setText(layer.isFovShowing(c.id) ? "Hide bearing" : "Show bearing");
        if (!c.hasFov()) {
            play.setEnabled(false);
            play.setText("No bearing reported");
        }

        // Slider spans 0..60 km / ~37 mi, the sensor maximum ATAK will honour.
        range.setMax((int) Math.ceil(SensorDetailHandler.MAX_SENSOR_RANGE
                / Units.bigToMetres(1)));
        range.setProgress((int) Math.round(layer.getRangeMetres()
                / Units.bigToMetres(1)));
        rangeLabel.setText("Bearing line reaches out " + Units.format(layer.getRangeMetres()));
        range.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                final double m = Units.bigToMetres(Math.max(1, p));
                rangeLabel.setText("Bearing line reaches out " + Units.format(m));
                if (user)
                    layer.setRangeMetres(m);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });

        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View b) {
                if (layer.isFovShowing(c.id)) {
                    layer.hideFov(c.id);
                    ((Button) b).setText("Show bearing");
                } else {
                    layer.showFov(c);
                    ((Button) b).setText("Hide bearing");
                    layer.goTo(c);
                }
            }
        });

        final Button video = v.findViewById(R.id.video);
        // Say up front whether this camera can stream. Only Caltrans publishes HLS;
        // the rest are stills, and offering "Video" on those promises something ATAK
        // cannot deliver.
        video.setText(c.hasStream() ? "\u25b6 Live video" : "Video (still only)");
        video.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View b) {
                playVideo(c);
            }
        });

        final Runnable load = new Runnable() {
            @Override
            public void run() {
                loadImage(c, image, info);
            }
        };
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View b) {
                load.run();
            }
        });

        if (detailHost != null) {
            v.findViewById(R.id.goto_cam).setVisibility(View.VISIBLE);
            v.findViewById(R.id.close_pane).setVisibility(View.VISIBLE);

            // A stills camera's pane is the picture and nothing else.
            //
            // The bearing-line controls belong to cameras that stream, where the line
            // answers "how far can it see" and is worth tuning. On a stills camera the
            // operator opened the pane to look at a photograph; a range slider reading
            // "38.00 mi", a bearing toggle and a dead video button are all answering
            // questions nobody asked. The bearing still shows on the MAP while the
            // picture is open -- that part they wanted -- it just is not driven from
            // here.
            if (!c.hasStream()) {
                rangeLabel.setVisibility(View.GONE);
                range.setVisibility(View.GONE);
                play.setVisibility(View.GONE);
                v.findViewById(R.id.video).setVisibility(View.GONE);
            }
            v.findViewById(R.id.goto_cam).setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View b) {
                            layer.goTo(c);
                        }
                    });
            v.findViewById(R.id.close_pane).setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View b) {
                            if (autoFov)
                                layer.hideFov(c.id);
                            detailHost.hideDetailPane();
                        }
                    });
            detailHost.showDetailPane(v);
            load.run();
            return;
        }

        new AlertDialog.Builder(ui)
                .setTitle(c.label())
                .setView(v)
                .setPositiveButton("Go to", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        layer.goTo(c);
                    }
                })
                .setNegativeButton("Close", null)
                .setOnDismissListener(
                        new android.content.DialogInterface.OnDismissListener() {
                            @Override
                            public void onDismiss(android.content.DialogInterface d) {
                                if (autoFov)
                                    layer.hideFov(c.id);
                            }
                        })
                .show();

        load.run();
    }

    /**
     * Hand the camera's image URL to ATAK's video player.
     *
     * <p>ALERT publishes stills, not a stream, and ATAK's player is libVLC — so a
     * single JPEG may well not play. This is wired so that can be <em>tested</em>
     * rather than argued about: if it fails, the fix is an MJPEG endpoint on our
     * proxy that re-fetches the still, which is what the previous developer built.
     */
    private void playVideo(Camera c) {
        if (c.hasStream()) {
            // A real HLS stream. This is the only case ATAK's player can actually
            // render; everything else in the catalog is a single JPEG.
            launchVideo(c, c.stream);
            return;
        }
        final String url = store.imageUrl(c);
        if (url == null) {
            store.loadImages(currentState, new Runnable() {
                @Override
                public void run() {
                    final String u = store.imageUrl(c);
                    if (u == null)
                        toast("No image URL for this camera yet");
                    else
                        launchVideo(c, u);
                }
            });
            return;
        }
        launchVideo(c, url);
    }

    private void launchVideo(Camera c, String url) {
        try {
            final gov.tak.api.video.ConnectionEntry ce =
                    com.atakmap.android.video.StreamManagementUtils
                            .createConnectionEntryFromUrl(c.label(), url);
            if (ce == null) {
                toast("ATAK would not accept that URL");
                return;
            }
            final android.content.Intent i =
                    new android.content.Intent("com.atakmap.maps.video.DISPLAY");
            i.putExtra("CONNECTION_ENTRY", ce);
            i.putExtra("cancelClose", "true");
            com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(i);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "video launch failed", e);
            toast("Could not open the video player");
        }
    }

    private String describe(Camera c) {
        final StringBuilder b = new StringBuilder();
        b.append(c.provider);
        if (c.sponsor != null && !c.sponsor.isEmpty())
            b.append(" / ").append(c.sponsor);
        b.append(c.offline ? "   OFFLINE" : "   online");
        b.append(c.ptz ? "   PTZ" : "   fixed");
        if (c.hasStream())
            b.append("   live stream");
        if (!c.county.isEmpty())
            b.append('\n').append(c.county).append(", ").append(c.state);
        if (!Double.isNaN(c.pan))
            b.append(String.format(Locale.US, "\npan %.1f°  tilt %.1f°  fov %.1f°",
                    c.pan, c.tilt, c.fov));
        return b.toString();
    }

    /** Fetch the image filenames if we do not have them yet, then the JPEG. */
    private void loadImage(final Camera c, final ImageView into, final TextView info) {
        final String url = store.imageUrl(c);
        if (url == null) {
            store.loadImages(currentState, new Runnable() {
                @Override
                public void run() {
                    final String u = store.imageUrl(c);
                    if (u == null)
                        info.append("\n(no image available)");
                    else
                        fetchInto(u, into, c.id);
                }
            });
            return;
        }
        fetchInto(url, into, c.id);
    }

    /**
     * Decoder thread. JPEG decode is not main-thread work.
     *
     * <p>{@link Http} delivers on the main thread so callers can touch views, which is
     * right for the bytes and wrong for what came in them: these frames are 1920x1080,
     * and decoding one on the UI thread is a visible stall every time a camera opens.
     * Same mistake as the marker churn that ANR'd ATAK, in a smaller place.
     */
    private static final java.util.concurrent.ExecutorService DECODER =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            final Thread t = new Thread(r, "camdepot-decode");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    /**
     * The last frame seen per camera, so reopening one paints immediately.
     *
     * <p>Small on purpose. It exists to cover the switch back and forth between two or
     * three cameras, not to be an image store — and a stale frame is only ever shown
     * while a fresh one is already on its way.
     */
    private final android.util.LruCache<String, Bitmap> frames =
            new android.util.LruCache<>(6);

    private void fetchInto(String url, final ImageView into) {
        fetchInto(url, into, null);
    }

    /**
     * @param key camera id; the view is tagged with it so a slow response cannot
     *            land in a pane the operator has already moved on from
     */
    private void fetchInto(String url, final ImageView into, final String key) {
        if (key != null) {
            into.setTag(key);
            final Bitmap had = frames.get(key);
            if (had != null)
                into.setImageBitmap(had);   // something to look at while we fetch
        }
        Http.get(url, new Http.Callback() {
            @Override
            public void onSuccess(final byte[] body) {
                DECODER.execute(new Runnable() {
                    @Override
                    public void run() {
                        // Half size is still larger than the view and decodes in a
                        // quarter of the pixels.
                        final BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inSampleSize = 2;
                        final Bitmap bm = BitmapFactory.decodeByteArray(
                                body, 0, body.length, o);
                        if (bm == null)
                            return;
                        if (key != null)
                            frames.put(key, bm);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (key == null || key.equals(into.getTag()))
                                    into.setImageBitmap(bm);
                            }
                        });
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                Log.w(TAG, "image fetch failed: " + error);
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(mapView.getContext(), s, Toast.LENGTH_SHORT).show();
    }

    // ---- list -------------------------------------------------------------

    private final class CameraAdapter extends BaseAdapter {

        private List<Camera> items = new ArrayList<>();
        private GeoPoint from;

        void setItems(List<Camera> items, GeoPoint from) {
            this.items = items;
            this.from = from;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Camera getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null)
                v = PluginLayoutInflater.inflate(pluginContext,
                        R.layout.camera_row, null);
            final Camera c = getItem(position);

            final TextView name = v.findViewById(R.id.name);
            final TextView detail = v.findViewById(R.id.detail);
            final View dot = v.findViewById(R.id.dot);

            name.setText(c.label());
            final StringBuilder d = new StringBuilder();
            d.append(c.state).append("  ").append(c.provider);
            if (!c.county.isEmpty())
                d.append("  ").append(c.county);
            if (from != null) {
                d.append("  ").append(Units.format(
                        c.metresFrom(from.getLatitude(), from.getLongitude())));
            }
            if (c.hasStream())
                d.append("  \u25b6 live");
            if (c.offline)
                d.append("  OFFLINE");
            detail.setText(d.toString());

            final GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c.offline ? 0xFF808080
                    : (c.fire ? 0xFFFF6600 : 0xFF33B5E5));
            dot.setBackground(g);

            final Button go = v.findViewById(R.id.goto_btn);
            go.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View b) {
                    layer.goTo(c);
                }
            });

            // The row opens the camera, and it has to do it itself.
            //
            // The list runs with setItemsCanFocus(true), which the search box needs --
            // it lives in a header view and a ListView otherwise refuses focus to its
            // children, so the box could not be typed into at all. The cost is that
            // rows containing a focusable child stop delivering
            // OnItemClickListener: the "Go to" Button takes the touch and the row
            // click never fires. That silently made the camera detail, and with it
            // "Show bearing", unreachable from the panel -- the only way left to a
            // bearing was ATAK's radial.
            //
            // Setting the listener on the row itself keeps both: the box still takes
            // focus, and the row is still a click target.
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View b) {
                    showDetail(c);
                }
            });
            return v;
        }
    }
}
