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
import com.atakmap.android.camdepot.data.Favorites;
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
    /**
     * How often the moving half of the catalog is re-read.
     *
     * <p>Matched to the proxy's own upstream poll (METADATA_INTERVAL, 15 s) because
     * there is nothing to gain by asking faster: the proxy would hand back the same
     * numbers until its next fetch. 15 s is also the floor worth having upstream --
     * ALERT's only endpoint returns the whole 6.4 MB catalog, so polling it every
     * 15 s is already ~36 GB a day taken from a wildfire nonprofit, and one poll is
     * shared by every EUD. Going faster than this is not a tuning decision, it is a
     * different data source.
     *
     * <p>Costs the EUD 22 bytes on a tick where nothing moved, which is nearly all of
     * them -- the feed answers with a delta, not the whole state.
     */
    private static final long REFRESH_MS = 3_000;

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
    /**
     * Turns the whole panel into the favorites list. Its own control rather than a
     * fourth checkbox beside Video / Still / Fire, because it is not the same kind of
     * filter: those narrow the selected state, this one leaves the state behind.
     */
    private final Button favoritesButton;
    private final Button bearingsOffButton;
    private final EditText search;
    /**
     * Clears the search box, and is disabled while it is empty.
     *
     * <p>So it is the indicator as much as the control: a live Clear button is the
     * only thing on the panel that says a search is narrowing the list. An operator
     * lost time to a search left in the box from earlier and had to open the
     * keyboard to find out.
     */
    private final Button searchClear;
    private final CheckBox fireOnly, liveOnly, stillOnly;
    /**
     * Narrows the list to the map's current extent, and follows it.
     *
     * <p>The map layer has always drawn by viewport; the list was the half that did
     * not follow. ATAK's own Overlay Manager has the same toggle, so this is the
     * behavior an operator already expects rather than a new idea.
     */
    private final CheckBox inView;
    private final SeekBar radius;
    private final TextView radiusLabel, zoomLabel;
    private final ListView list;
    /** Holds the filter controls; they are a header view, not part of the pane. */
    private final View controls;
    private final CameraAdapter adapter;

    /**
     * The operator's marked cameras, and the one piece of state in this panel that is
     * <em>not</em> scoped to {@link #currentState}. See {@link Favorites}.
     */
    private final Favorites favorites;
    /**
     * True while favorites are pinned above the ordinary list.
     *
     * <p>Not a mode that <em>replaces</em> the list. An operator wants their own
     * cameras to hand and the rest of the catalog underneath them in the same
     * scroller, so Go to is one tap away from either.
     */
    private boolean favoritesFirst;
    /**
     * The status line's resting color, captured before anything recolors it.
     *
     * <p>Read from the view rather than hardcoded so the busy line goes back to
     * whatever ATAK's theme actually uses, not to a guess at it.
     */
    private final int statusColor;

    private String currentState = "CA";
    private final List<String> stateCodes = new ArrayList<>();
    private final List<String> providerNames = new ArrayList<>();
    private final List<String> countyNames = new ArrayList<>();
    /** null means "all". */
    private String selectedProvider;
    /**
     * The counties in play. Empty means every county in the state.
     *
     * <p>A set rather than one value because an operating area is rarely one
     * county: a fire runs across three, a district covers five. Picking them one
     * at a time meant filtering to a county, looking, then filtering to the next
     * and losing the first -- so the panel could never show the area an operator
     * was actually working.
     */
    private final java.util.Set<String> selectedCounties =
            new java.util.LinkedHashSet<>();
    /** Radius in the operator's own unit (miles / km / NM). 0 means no filter. */
    private double radiusBig = 0;
    private GeoPoint center;                  // null means "use my location"

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            if (store.isLoaded(currentState)) {
                // Every loaded state, not just the selected one: search puts other
                // states' cameras on the map and they must track too.
                store.setCurrentState(currentState);
                store.refreshAllLive();
            }
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
            // Only when the operator asked the list to follow the map. Without the
            // toggle this stays what it was -- re-filtering and re-sorting thousands
            // of cameras on every pan is work for nothing, and the comment above is
            // still true for that case.
            if (inView.isChecked())
                apply();
            else
                updateStatus();
        }
    };

    public CamDepotPane(Context pluginContext, MapView mapView, String baseUrl) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
        this.layer = new CameraLayer(mapView, pluginContext);
        this.store = new CameraStore(baseUrl, this);
        // MapView context: these have to survive the plugin being reloaded.
        this.favorites = new Favorites(mapView.getContext());

        root = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
        status = root.findViewById(R.id.status);
        statusColor = status.getCurrentTextColor();
        list = root.findViewById(R.id.cameras);
        legend(root.findViewById(R.id.legend));

        // The filters ride as a header of the list so the panel has exactly one
        // scroller. See the comment in controls_header.xml.
        controls = PluginLayoutInflater.inflate(pluginContext,
                R.layout.controls_header, null);
        list.addHeaderView(controls, null, false);
        favoritesButton = controls.findViewById(R.id.favorites);
        bearingsOffButton = controls.findViewById(R.id.bearings_off);
        stateButton = controls.findViewById(R.id.state);
        providerButton = controls.findViewById(R.id.provider);
        countyButton = controls.findViewById(R.id.county);
        search = controls.findViewById(R.id.search);
        searchClear = controls.findViewById(R.id.search_clear);
        inView = controls.findViewById(R.id.in_view);
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

        // Before wire(), so restoring a checkbox does not fire its own listener and
        // run apply() against a panel that is not built yet.
        restoreUi();

        wire();
        updateFavoritesButton();
        updateBearingsOffButton();
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

        /**
         * The next close of the detail pane is ATAK taking the slot for itself, so
         * do not put the camera list back over the top of it.
         *
         * <p>Launching the video player closes our pane from underneath us, which is
         * indistinguishable from the operator pressing Close. Restoring the list
         * there covered the video the instant it opened, and read as "play sends me
         * back to the list".
         *
         * <p>Showing the list first instead, so the video would stack above it and
         * the back key could unwind to it, was tried and is worse: the restore is
         * delivered asynchronously and landed after the player had opened, so the
         * video did not play at all. The slot goes to the player, and nothing else
         * touches it.
         */
        void keepListClosedOnce();
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
                searchClear.setEnabled(search.getText().length() > 0);
                if (search.getText().length() > 0 && !loadedAll) {
                    loadedAll = true;
                    busy("Building the camera list for every state…");
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
        searchClear.setEnabled(search.getText().length() > 0);
        searchClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                search.setText("");
                // Put the keyboard away too. Clearing a search and then having to
                // dismiss a keyboard by hand is half a fix.
                try {
                    final android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    mapView.getContext().getSystemService(
                                            Context.INPUT_METHOD_SERVICE);
                    if (imm != null)
                        imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
                } catch (RuntimeException ignored) {
                    // The list is cleared either way; the keyboard is a courtesy.
                }
                search.clearFocus();
            }
        });

        final CompoundButton.OnCheckedChangeListener cc =
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        apply();
                    }
                };
        inView.setOnCheckedChangeListener(cc);
        fireOnly.setOnCheckedChangeListener(cc);
        liveOnly.setOnCheckedChangeListener(cc);
        stillOnly.setOnCheckedChangeListener(cc);

        bearingsOffButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final int n = layer.fovCount();
                layer.hideAllFovs();
                updateBearingsOffButton();
                toast(n == 1 ? "Bearing line off"
                        : String.format(Locale.US, "%d bearing lines off", n));
            }
        });

        controls.findViewById(R.id.resync).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // Everything, from the catalog down.
                        //
                        // The shards are cached hard and keyed to the catalog's
                        // publish time, which is correct until the catalog changes
                        // under a running plugin -- a camera's video address moving,
                        // an agency being added. Nothing re-read the catalog within a
                        // session, so the only cure was to force-stop ATAK. That is
                        // not something to ask of someone in the field, and it is the
                        // first thing anyone tries when the panel looks wrong.
                        busy("Syncing with the camera service\u2026");
                        layer.clear();
                        // Also drop the video entries registered from the OLD
                        // catalog. Without this a sync reloads every camera and
                        // still plays the previous stream URL, because both
                        // registration paths short-circuit on an entry ATAK still
                        // holds -- which is precisely what happened when the live
                        // host moved and only an ATAK restart fixed it.
                        layer.forgetVideoEntries();
                        loadedAll = false;
                        store.reset();
                        store.loadCatalog();
                    }
                });

        favoritesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                favoritesFirst = !favoritesFirst;
                // A favorite in a state that has never been selected has no name and
                // no position until its static shard is in. Same fetch global search
                // makes, and it only ever happens once a session.
                if (favoritesFirst && !loadedAll) {
                    loadedAll = true;
                    busy("Building the camera list so favorites outside "
                            + currentState + " can be listed\u2026");
                    store.loadAllStates(new Runnable() {
                        @Override
                        public void run() {
                            apply();
                        }
                    });
                }
                updateFavoritesButton();
                apply();
            }
        });

        stateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose("State", stateCodes, currentState, new Chosen() {
                    @Override
                    public void onChosen(String value) {
                        if (value.equals(currentState) && store.isLoaded(value))
                            return;
                        currentState = value;
                        selectedCounties.clear();
                        layer.clear();
                        stateButton.setText(value);
                        countyButton.setText("All counties");
                        busy(String.format(Locale.US,
                                "Building camera list for %s…", value));
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
                chooseCounties();
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
                                                setThreshold(presetMeters(i)
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
                // Center on where the map is looking. Simple, predictable, and it
                // works whether or not the device has a GPS fix.
                center = mapView.getPoint().get();
                toast("Radius now measured from the map center");
                apply();
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                // Position includes the header view, so shift back into the adapter.
                final int i = pos - list.getHeaderViewsCount();
                if (i < 0 || i >= adapter.getCount())
                    return;
                final Object row = adapter.getItem(i);
                if (row instanceof Camera)
                    showDetail((Camera) row);
            }
        });
    }

    /**
     * Quote the scale bar, because it is already on screen.
     *
     * <p>Earlier versions of this readout invented their own vocabulary — meters per
     * pixel, then named bands like "county level" — and both made the operator
     * translate between the panel and the map. The bar in the lower left is the
     * reference they already have, so the threshold is expressed in the same terms.
     */
    static final String ALWAYS = "Always draw them";
    static final String PREF_ZOOM = "camdepot_zoom_threshold";
    /**
     * What the panel looked like last time, so opening the plugin does not mean
     * setting it all up again.
     *
     * <p>The search box and the radius are deliberately NOT among these. Both are
     * per-task rather than per-operator: coming back to a search you typed
     * yesterday, or to a radius measured from where you were standing then, is a
     * filter you did not choose and would have to notice before you could clear it.
     * Everything here is a standing preference -- where you work and what you care
     * to see.
     */
    private static final String PREF_STATE = "camdepot_state";
    private static final String PREF_PROVIDER = "camdepot_provider";
    private static final String PREF_COUNTY = "camdepot_county";
    private static final String PREF_FIRE = "camdepot_fire_only";
    private static final String PREF_VIDEO = "camdepot_video_only";
    private static final String PREF_STILL = "camdepot_still_only";
    private static final String PREF_FAV_FIRST = "camdepot_favorites_first";
    private static final String PREF_IN_VIEW = "camdepot_in_view";

    /**
     * Starting points, expressed as what the scale bar would read — the same
     * vocabulary the readout uses. Presets exist so the plugin is useful before the
     * operator has tuned anything; "Use this zoom" is what they reach for once they
     * know what they want, and it overrides any preset.
     */
    /**
     * Preset scale-bar distances, in the operator's <em>own</em> large unit rather
     * than in meters.
     *
     * <p>Round meters are not round miles: a 1600 m preset labels itself "1.0 mi",
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

    private static double presetMeters(int i) {
        return Units.bigToMeters(PRESET_BAR_BIG[i]);
    }

    private static String presetLabel(int i) {
        final double n = PRESET_BAR_BIG[i];
        final String num = n == Math.floor(n)
                ? String.format(Locale.US, "%.0f", n)
                : String.format(Locale.US, "%.2f", n);
        return num + " " + Units.bigLabel() + "  —  " + PRESET_NAMES[i];
    }

    /** Set the threshold, remember it, and redraw. The one path all controls use. */
    private void setThreshold(double metersPerPixel) {
        layer.setMaxResolution(metersPerPixel);
        try {
            android.preference.PreferenceManager
                    .getDefaultSharedPreferences(mapView.getContext())
                    .edit().putFloat(PREF_ZOOM, (float) metersPerPixel).apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "could not remember the zoom threshold", e);
        }
        updateZoomLabel();
        apply();
    }

    private android.content.SharedPreferences prefs() {
        try {
            return android.preference.PreferenceManager
                    .getDefaultSharedPreferences(mapView.getContext());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Put the panel back the way it was left. */
    private void restoreUi() {
        final android.content.SharedPreferences p = prefs();
        if (p == null)
            return;
        try {
            currentState = p.getString(PREF_STATE, currentState);
            final String pr = p.getString(PREF_PROVIDER, "");
            final String co = p.getString(PREF_COUNTY, "");
            // Empty means "all", which is what null means to the filters. These are
            // validated again in onCameras once the state is loaded, so a provider
            // that no longer exists here quietly falls back to all rather than
            // matching nothing.
            selectedProvider = pr.isEmpty() ? null : pr;
            selectedCounties.clear();
            for (String one : co.split("\u001f")) {
                if (!one.isEmpty())
                    selectedCounties.add(one);
            }
            fireOnly.setChecked(p.getBoolean(PREF_FIRE, false));
            liveOnly.setChecked(p.getBoolean(PREF_VIDEO, false));
            stillOnly.setChecked(p.getBoolean(PREF_STILL, false));
            inView.setChecked(p.getBoolean(PREF_IN_VIEW, false));
            favoritesFirst = p.getBoolean(PREF_FAV_FIRST, false);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not restore the panel; starting fresh", e);
        }
    }

    /** The panel's current shape, as one string, so a write only happens on change. */
    private String uiSignature() {
        return currentState + "|" + (selectedProvider == null ? "" : selectedProvider)
                + "|" + joinCounties()
                + "|" + fireOnly.isChecked() + liveOnly.isChecked()
                + stillOnly.isChecked() + inView.isChecked() + favoritesFirst;
    }

    private String savedUi = "";

    /**
     * Remember the panel, but only when it actually changed.
     *
     * <p>apply() runs on every live refresh, which is every few seconds; writing
     * preferences on that cadence would be a disk write for nothing nearly every
     * time. Comparing a short signature first makes it a write per operator action.
     */
    private void rememberUi() {
        final String now = uiSignature();
        if (now.equals(savedUi))
            return;
        savedUi = now;
        final android.content.SharedPreferences p = prefs();
        if (p == null)
            return;
        try {
            p.edit()
                    .putString(PREF_STATE, currentState)
                    .putString(PREF_PROVIDER,
                            selectedProvider == null ? "" : selectedProvider)
                    .putString(PREF_COUNTY, joinCounties())
                    .putBoolean(PREF_FIRE, fireOnly.isChecked())
                    .putBoolean(PREF_VIDEO, liveOnly.isChecked())
                    .putBoolean(PREF_STILL, stillOnly.isChecked())
                    .putBoolean(PREF_IN_VIEW, inView.isChecked())
                    .putBoolean(PREF_FAV_FIRST, favoritesFirst)
                    .apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "could not remember the panel", e);
        }
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
     * The marker colors, spelled out. They were previously discoverable only by
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

    private static void swatch(android.text.SpannableStringBuilder b, int color,
            String label) {
        final int at = b.length();
        b.append("\u25cf");
        b.setSpan(new android.text.style.ForegroundColorSpan(color), at, b.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append(" ").append(label);
    }

    /**
     * Star or hollow star, and the count. The count is on the control for the same
     * reason the provider and Video/Still/Fire filters carry theirs: a filter should
     * say what it will cost before it is used.
     */
    private void updateFavoritesButton() {
        final int n = favorites.size();
        if (!favoritesFirst) {
            favoritesButton.setText(String.format(Locale.US,
                    "\u2606 Favorites (%d)", n));
            return;
        }
        // Gold star, the same gold the starred rows wear. The button is a filter that
        // stays on across everything else the operator does, so it has to read as ON
        // from across the panel rather than by being read word by word.
        final String text = String.format(Locale.US, "\u2605 Favorites First (%d)", n);
        final android.text.SpannableString styled =
                new android.text.SpannableString(text);
        styled.setSpan(new android.text.style.ForegroundColorSpan(STAR_ON), 0, 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        favoritesButton.setText(styled);
    }

    /**
     * The count of drawn bearing lines, and disabled at zero.
     *
     * <p>Disabled rather than hidden: a control that appears and disappears is one
     * the operator has to hunt for, and the greyed-out button is itself the answer
     * to "is anything still drawn out there".
     */
    private void updateBearingsOffButton() {
        final int n = layer.fovCount();
        bearingsOffButton.setEnabled(n > 0);
        bearingsOffButton.setText(n == 0 ? "No bearings shown"
                : String.format(Locale.US, "Turn off %d bearing%s",
                        n, n == 1 ? "" : "s"));
    }

    /** Marked. The one gold in the plugin, so a star is a star wherever it appears. */
    private static final int STAR_ON = 0xFFFFC107;
    /** Unmarked: present enough to be found, quiet enough not to be a row of stars. */
    private static final int STAR_OFF = 0x66FFFFFF;

    /** Paint one star, wherever it lives. */
    private void styleStar(TextView star, String id) {
        final boolean on = favorites.contains(id);
        star.setText(on ? "\u2605" : "\u2606");
        star.setTextColor(on ? STAR_ON : STAR_OFF);
    }

    /**
     * Mark or unmark, then make every view that shows it agree.
     *
     * <p>The same camera can be starred in the row and in the detail pane at the same
     * moment, and the count sits on a third control.
     */
    private void toggleFavorite(Camera c, TextView star) {
        favorites.toggle(c.id);
        styleStar(star, c.id);
        updateFavoritesButton();
        adapter.notifyDataSetChanged();
        if (favoritesFirst)
            apply();
    }

    /**
     * Say, in color, that the plugin is working.
     *
     * <p>Switching state fetches a shard and parses it off the main thread, which on
     * New York is a couple of seconds of a stale list sitting there looking finished.
     * The message existed already and read as part of the furniture; green separates
     * "I am doing something" from the ordinary count line without adding a spinner.
     * Green rather than blue on purpose -- blue is already the DOT/FAA swatch in the
     * legend directly above, and a second meaning for the same color in the same
     * panel is how a legend stops being one.
     */
    private static final int STATUS_BUSY = 0xFF4CAF50;

    private void busy(String message) {
        status.setTextColor(STATUS_BUSY);
        status.setText(message);
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
        final double m = ScaleBar.meters(mapView);
        return m > 0 ? m / res : 200;
    }

    private void setRadiusFromProgress(int progress) {
        radiusBig = progress * 2;               // 0..400 in the operator's unit
        radiusLabel.setText(radiusBig <= 0
                ? "Radius: off — the whole state"
                : String.format(Locale.US, "Within %.0f %s of %s", radiusBig,
                        Units.bigLabel(),
                        center == null ? "my location" : "the map center"));
    }

    // ---- store callbacks --------------------------------------------------

    @Override
    public void onCatalog(Catalog catalog) {
        stateCodes.clear();
        for (Catalog.State st : catalog.states)
            stateCodes.add(st.code);
        // Alphabetical, not publisher order. Forty entries in whatever sequence the
        // catalog happened to be generated in is a list you have to read rather than
        // scan, and the operator already knows which state they want.
        Collections.sort(stateCodes, String.CASE_INSENSITIVE_ORDER);


        stateButton.setText(currentState);
        providerButton.setText(selectedProvider == null ? ALL : selectedProvider);
        updateCountyButton();

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
        // Keep only what this state actually has. A county remembered from
        // somewhere else would filter to nothing and look like a broken panel.
        if (selectedCounties.retainAll(countyNames))
            updateCountyButton();
        // Favorites are cross-state, so a saved one is very often in a state this
        // session has never selected -- and an id with no static record has no name
        // and nowhere to go to. Fetch the rest now, once the first state is in, so
        // the Favorites button is instant when it is pressed. Deferred to here rather
        // than run from onCatalog because loadAllStates skips states already loaded,
        // and at onCatalog time the selected one has not arrived yet: starting both
        // there fetches it twice.
        if (!loadedAll && !favorites.isEmpty()) {
            loadedAll = true;
            store.loadAllStates(new Runnable() {
                @Override
                public void run() {
                    apply();
                }
            });
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
        // ATAK's radial can turn a bearing on or off without the panel hearing about
        // it, so the count is re-read here rather than only where the panel itself
        // toggles one. apply() runs on every filter change and on a settled map move.
        updateBearingsOffButton();
        final String query = search.getText().toString().trim();
        // A name search looks at every state, not just the loaded one. Typing "Moses"
        // while on California found nothing because Moses is in Nevada and Washington,
        // and no one types a camera name expecting it to only search where they happen
        // to be standing. Without a query, behavior is unchanged: the selected state.
        final List<Camera> all = query.isEmpty()
                ? store.cameras(currentState)
                : store.everywhere();
        final String q = query.toLowerCase(Locale.US);
        final String provider = selectedProvider;
        final java.util.Set<String> counties =
                new java.util.HashSet<>(selectedCounties);

        // The map's current extent, read once rather than per camera. Compared as
        // bare doubles instead of GeoBounds.contains(GeoPoint) so a pan does not
        // allocate a GeoPoint per camera across a 30,000-camera catalog.
        final boolean onScreen = inView.isChecked();
        final com.atakmap.coremap.maps.coords.GeoBounds view =
                onScreen ? mapView.getBounds() : null;
        final double vN = view == null ? 0 : view.getNorth();
        final double vS = view == null ? 0 : view.getSouth();
        final double vW = view == null ? 0 : view.getWest();
        final double vE = view == null ? 0 : view.getEast();
        final boolean idl = view != null && view.crossesIDL();

        final boolean fire = fireOnly.isChecked();
        final boolean live = liveOnly.isChecked();
        final boolean still = stillOnly.isChecked();

        GeoPoint from = center;
        final double radiusMeters = radiusBig > 0 ? Units.bigToMeters(radiusBig) : 0;
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
            // County belongs to a state, so it is not applied to a cross-state
            // search result -- that would drop every hit from elsewhere.
            if (q.isEmpty() && !counties.isEmpty() && !counties.contains(c.county))
                continue;
            if (!q.isEmpty() && !matchesQuery(c, q))
                continue;
            if (radiusMeters > 0 && from != null
                    && c.metersFrom(from.getLatitude(), from.getLongitude())
                            > radiusMeters)
                continue;
            if (onScreen && !inside(c, vN, vS, vW, vE, idl))
                continue;
            out.add(c);
        }

        final GeoPoint sortFrom = from;

        // The favorites section is gathered separately and from everywhere(), never
        // from the filtered result. Favorites are cross-state by nature, so anything
        // that reads them out of cameras(currentState) -- or out of a list the state,
        // county, radius and Show filters have already been over -- lists only the
        // ones that happen to match the panel right now. That assumption is what
        // caused five separate bugs on 2026-08-27; it does not get to come back in
        // through the favorites list of all places.
        //
        // Only the search box narrows this section, because that is the operator
        // typing this second rather than a filter left set an hour ago.
        final List<Camera> favs = new ArrayList<>();
        if (favoritesFirst && !favorites.isEmpty()) {
            for (Camera c : store.everywhere()) {
                if (c.offline || !favorites.contains(c.id))
                    continue;
                if (!q.isEmpty() && !matchesQuery(c, q))
                    continue;
                favs.add(c);
            }
            // A favorite in the current state would otherwise appear twice, once in
            // each section.
            final java.util.Set<String> pinned = new java.util.HashSet<>();
            for (Camera c : favs)
                pinned.add(c.id);
            for (java.util.Iterator<Camera> it = out.iterator(); it.hasNext(); ) {
                if (pinned.contains(it.next().id))
                    it.remove();
            }
        }

        sortForList(favs, sortFrom);
        sortForList(out, sortFrom);

        // The count belongs on the control: a filter says what it will cost before
        // it is used, and this one's cost changes every time the map moves.
        // out only, not the pinned favorites: those are deliberately exempt from
        // every filter including this one, and each section carries its own count.
        inView.setText(onScreen
                ? String.format(Locale.US, "On screen only (%,d)", out.size())
                : "On screen only");

        adapter.setSections(favs, out, sortFrom, currentState, query);

        // The layer takes the whole selection and decides what to draw from the
        // current viewport, so zooming in reveals cameras rather than keeping a set
        // chosen when the map was somewhere else. Favorites go in too, or a Go to
        // from the pinned section arrives at empty ground.
        final List<Camera> drawn = new ArrayList<>(favs);
        drawn.addAll(out);
        layer.show(drawn);

        rememberUi();

        lastFavorites = favs.size();
        lastMatched = out.size();
        lastTotal = all.size();
        updateStatus();
    }

    /** Nearest first when there is somewhere to measure from, by name otherwise. */
    private static void sortForList(List<Camera> cameras, final GeoPoint from) {
        if (from != null) {
            Collections.sort(cameras, new Comparator<Camera>() {
                @Override
                public int compare(Camera a, Camera b) {
                    return Double.compare(
                            a.metersFrom(from.getLatitude(), from.getLongitude()),
                            b.metersFrom(from.getLatitude(), from.getLongitude()));
                }
            });
        } else {
            Collections.sort(cameras, new Comparator<Camera>() {
                @Override
                public int compare(Camera a, Camera b) {
                    return a.label().compareToIgnoreCase(b.label());
                }
            });
        }
    }

    /**
     * Is this camera inside the map's current extent?
     *
     * <p>The longitude test flips when the view straddles the antimeridian: west is
     * then numerically greater than east, so "outside" becomes an AND rather than an
     * OR. Nothing in the catalog is near the line today, and a filter that silently
     * empties itself over the Pacific is the kind of thing found much later.
     */
    private static boolean inside(Camera c, double n, double s, double w, double e,
            boolean idl) {
        if (c.lat > n || c.lat < s)
            return false;
        return idl ? !(c.lon < w && c.lon > e) : !(c.lon < w || c.lon > e);
    }

    /** @param q the search box, already trimmed and lower-cased */
    private static boolean matchesQuery(Camera c, String q) {
        return c.label().toLowerCase(Locale.US).contains(q) || c.id.contains(q);
    }

    private int lastMatched, lastTotal, lastFavorites;
    private boolean loadedAll;

    private void updateStatus() {
        // Three separate facts, and cramming them into one sentence made the panel
        // unreadable: how many cameras exist, how many the filters kept, and how many
        // of those actually got drawn. One line each, in that order.
        // Back to the resting color: whatever was being built has been.
        status.setTextColor(statusColor);
        final StringBuilder msg = new StringBuilder();
        msg.append(String.format(Locale.US, "%,d of %,d %s cameras up and matching",
                lastMatched, lastTotal, currentState));
        // Name the search in the status line. The list being short because of a
        // filter the operator forgot is exactly the confusion this panel is supposed
        // to prevent, and the box itself is easy to scroll past.
        final String q = search.getText().toString().trim();
        if (!q.isEmpty())
            msg.append(String.format(Locale.US, "  \u00b7  search: \u201c%s\u201d", q));
        if (favoritesFirst) {
            // A favorites list that is quietly shorter than its own count reads as
            // favorites having been lost.
            final int gone = favorites.size() - lastFavorites;
            msg.append(String.format(Locale.US, "\nFavorites: %,d pinned above",
                    lastFavorites));
            if (gone > 0)
                msg.append(String.format(Locale.US,
                        ", %,d not listed \u2014 offline, still loading, or not "
                                + "matching the search", gone));
        }
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

    /**
     * The chosen counties as one string for preferences.
     *
     * <p>Unit separator, not a comma: county names contain commas about as often
     * as they contain anything else, and a delimiter that can appear in the data
     * is a bug waiting for the right county name.
     */
    private String joinCounties() {
        final StringBuilder b = new StringBuilder();
        for (String c : selectedCounties) {
            if (b.length() > 0)
                b.append('\u001f');
            b.append(c);
        }
        return b.toString();
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
    /**
     * Pick any number of counties, on the MapView context.
     *
     * <p>Nothing is applied until OK, so half-made selections never reach the map,
     * and Clear is its own button because emptying a set by unticking a dozen
     * boxes is not a thing to ask of anyone.
     */
    private void chooseCounties() {
        final List<String> names = new ArrayList<>();
        for (String c : countyNames) {
            if (!ALL_COUNTIES.equals(c))
                names.add(c);
        }
        if (names.isEmpty()) {
            toast("This state publishes no counties");
            return;
        }
        final String[] arr = names.toArray(new String[0]);
        final boolean[] ticked = new boolean[arr.length];
        for (int i = 0; i < arr.length; i++)
            ticked[i] = selectedCounties.contains(arr[i]);

        new AlertDialog.Builder(mapView.getContext())
                .setTitle("Counties")
                .setMultiChoiceItems(arr, ticked,
                        new android.content.DialogInterface
                                .OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d,
                                    int which, boolean isChecked) {
                                ticked[which] = isChecked;
                            }
                        })
                .setPositiveButton("OK",
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d,
                                    int w) {
                                selectedCounties.clear();
                                for (int i = 0; i < arr.length; i++) {
                                    if (ticked[i])
                                        selectedCounties.add(arr[i]);
                                }
                                updateCountyButton();
                                apply();
                            }
                        })
                .setNeutralButton("Clear",
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d,
                                    int w) {
                                selectedCounties.clear();
                                updateCountyButton();
                                apply();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** One county by name, several by count, none as "All counties". */
    private void updateCountyButton() {
        final int n = selectedCounties.size();
        if (n == 0)
            countyButton.setText(ALL_COUNTIES);
        else if (n == 1)
            countyButton.setText(selectedCounties.iterator().next());
        else
            countyButton.setText(String.format(Locale.US, "%d counties", n));
    }

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

        info.setText(describe(c));

        final TextView star = v.findViewById(R.id.star);
        styleStar(star, c.id);
        star.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View b) {
                toggleFavorite(c, (TextView) b);
            }
        });

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

        // No bearing-length slider here, deliberately.
        //
        // It read as broken because on a stills camera it WAS: CameraLayer draws
        // those with a fixed 1 km line and never consults the slider, so on the FAA
        // cameras -- the first stills in the catalog with a real bearing -- it moved
        // and nothing happened. It did work on cameras that stream. Removed rather
        // than fixed at the operator's call: a per-camera length control is a knob
        // for a line whose job is to say which way the camera points, not how far it
        // sees. If it comes back it belongs in the panel as one setting, not in
        // every camera's pane.

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
                updateBearingsOffButton();
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

            // Every control in the pane earns its place or is not there.
            //
            // The bearing controls are gated on the camera REPORTING a bearing, and
            // on nothing else. Those are different questions from whether it
            // streams, and conflating them broke it twice in opposite directions:
            // first a range slider reading "37.28 mi" under a video camera whose own
            // button said "No bearing reported", then no controls at all on a stills
            // camera that does have one.
            //
            // The second is the worse of the two. Opening a stills camera turns its
            // bearing on automatically -- that is the point, it answers "what am I
            // looking at?" while the picture is up -- so a stills camera with no
            // toggle is a bearing the operator can switch on and not off. ATAK's
            // radial is no escape either: on these markers it flashes and closes.
            // The FAA cameras are the first stills in the catalog to publish a real
            // bearing, which is what surfaced it.
            final boolean bearing = c.hasFov();
            final View videoButton = v.findViewById(R.id.video);
            play.setVisibility(bearing ? View.VISIBLE : View.GONE);
            videoButton.setVisibility(c.hasStream() ? View.VISIBLE : View.GONE);
            // The whole row when nothing is left in it: an emptied row still carries
            // its own padding, and the pinned buttons below it are the one thing that
            // must not drift down the pane.
            v.findViewById(R.id.stream_actions).setVisibility(
                    bearing || c.hasStream() ? View.VISIBLE : View.GONE);
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
    /**
     * Play a camera, through the one entry the layer builds.
     *
     * <p>There used to be a fallback here that built a second ConnectionEntry with
     * {@code StreamManagementUtils.createConnectionEntryFromUrl}. It is gone, for
     * three reasons that all point the same way. It could not work: that shape is
     * the one CameraLayer records as not playing, which is why video played from
     * the radial and not from this button. It could not be reached: the video
     * control is hidden on a camera with no stream. And it could not be compiled
     * against every ATAK the fleet runs -- the method returns
     * {@code com.atakmap.android.video.ConnectionEntry} on 5.6 and
     * {@code gov.tak.api.video.ConnectionEntry} on 5.8, so it breaks a build for
     * an older target, which is exactly what a submission has to produce.
     */
    private void playVideo(Camera c) {
        launchVideo(c);
    }

    private void launchVideo(Camera c) {
        try {
            // Raw protocol, whole URL as the address -- the shape confirmed playing
            // on device, and the one the radial menu hands the player. videoEntry
            // returns null for a camera with no stream, or one whose published URL
            // is not https.
            final gov.tak.api.video.ConnectionEntry ce = layer.videoEntry(c);
            if (ce == null) {
                toast("This camera has no playable stream");
                return;
            }
            // Hand the player an EMPTY right-hand slot, then broadcast.
            //
            // ATAK's video player is a right-side dropdown, and our camera pane is
            // sitting in that slot. Broadcasting with it still open made ATAK log
            // "right side in use and the new drop down is not switchable" and evict
            // our pane in the middle of the player's own show sequence: the video
            // surface was never created, no track was discovered, and nothing
            // played. From the radial the same camera, with a byte-identical
            // ConnectionEntry, logged "right side is empty" and played every time.
            // The entry was never the difference; the occupied slot was.
            //
            // keepListClosedOnce() first, or closing the pane brings the camera list
            // back into the slot we are trying to empty -- which is the same failure
            // wearing a different hat, and is what an earlier attempt at this did.
            //
            // The delay lets the close actually land. closePane posts its own work,
            // so broadcasting in the same turn races it for the slot.
            if (detailHost != null) {
                detailHost.keepListClosedOnce();
                detailHost.hideDetailPane();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        broadcastVideo(c, ce);
                    }
                }, 250);
                return;
            }
            broadcastVideo(c, ce);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "video launch failed", e);
            toast("Could not open the video player");
        }
    }

    /** The broadcast itself, once the pane slot is clear. */
    private void broadcastVideo(Camera c, gov.tak.api.video.ConnectionEntry ce) {
        try {
            // Send the UID and let ATAK fetch its own entry -- exactly what the
            // radial does.
            //
            // ATAK's own actions/video.xml broadcasts videoUID and videoUrl as
            // STRINGS and never passes an entry. We were passing the whole
            // ConnectionEntry as a Parcelable, and on ATAK 5.6 that does not
            // survive the Intent round-trip: the radial played a camera while the
            // pane failed the same camera with "invalid video format", because the
            // entry ATAK holds in memory was fine and the one it unparcelled was
            // not. Sending the uid means both paths hand the player the identical
            // object -- the one VideoManager already holds.
            final android.content.Intent i =
                    new android.content.Intent("com.atakmap.maps.video.DISPLAY");
            i.putExtra("videoUID", ce.getUID());
            i.putExtra("videoUrl", ce.getAddress());
            i.putExtra("cancelClose", "true");
            com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(i);
        } catch (LinkageError | RuntimeException e) {
            Log.w(TAG, "video launch failed for " + c.id, e);
            toast("Could not open the video player");
        }
    }

    /**
     * The pane's own header.
     *
     * <p>The camera's name leads, because the pane did not say which camera it was
     * showing: it opened straight into the agency and the county, and an operator
     * with two panes' worth of Bear Mtn had nothing to tell them apart.
     *
     * <p>The pointing line is written to match the label ATAK draws on the bearing
     * itself -- {@code 113°T} in both places -- so the number in the pane and the
     * line on the map read as the same fact. {@code pan} is that bearing and was
     * never called one. {@code fov} is how wide the camera sees and is deliberately
     * NOT drawn on the map ({@code CameraLayer.attachSensor} sets fov=0: sixteen
     * hundred wedges is soup), so it is spelled out here in words rather than left
     * as a bare number beside a line that does not represent it.
     */
    private String describe(Camera c) {
        final StringBuilder b = new StringBuilder();
        b.append(c.label()).append('\n');
        b.append(c.provider);
        if (c.sponsor != null && !c.sponsor.isEmpty())
            b.append(" / ").append(c.sponsor);
        b.append(c.offline ? "   OFFLINE" : "   online");
        b.append(c.ptz ? "   PTZ" : "   fixed");
        if (c.hasStream())
            b.append("   live stream");
        if (!c.county.isEmpty())
            b.append('\n').append(c.county).append(", ").append(c.state);
        if (!Double.isNaN(c.pan)) {
            b.append(String.format(Locale.US, "\nBearing %.0f°T", c.pan));
            // Only when the agency published one. A camera can report where it is
            // pointing without saying how wide it sees, and "Field of View 0.0°"
            // would be a worse answer than saying nothing.
            if (!Double.isNaN(c.fov) && c.fov > 0)
                b.append(String.format(Locale.US, "   Field of View %.1f°", c.fov));
        }
        return b.toString();
    }

    /** Ask for the camera's current frame, then fetch it. */
    private void loadImage(final Camera c, final ImageView into, final TextView info) {
        // Live lookup first: the published shard is only as fresh as the last
        // publisher run, and that is by hand. liveImageUrl falls back to the shard
        // on its own when there is no proxy or the call fails.
        store.liveImageUrl(c, new CameraStore.UrlCallback() {
            @Override
            public void onUrl(String url) {
                if (url != null) {
                    fetchInto(url, into, c.id, info);
                    return;
                }
                loadImageFromShard(c, into, info);
            }
        });
    }

    /** Last resort: the state's images shard, which may not be loaded yet. */
    private void loadImageFromShard(final Camera c, final ImageView into,
            final TextView info) {
        final String url = store.imageUrl(c);
        if (url == null) {
            store.loadImages(currentState, new Runnable() {
                @Override
                public void run() {
                    final String u = store.imageUrl(c);
                    if (u == null) {
                        // Say which of the two it is. "No image available" reads as
                        // a broken camera on the 552 Maryland cameras that publish
                        // live video and no still at all -- there is nothing wrong
                        // with them, they simply never send a picture, and the
                        // button that does work is right there.
                        info.append(c.hasStream()
                                ? "\nNo still from this camera \u2014 tap Live video"
                                : "\nNo picture available from this camera");
                    } else {
                        fetchInto(u, into, c.id, info);
                    }
                }
            });
            return;
        }
        fetchInto(url, into, c.id, info);
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
        fetchInto(url, into, key, null);
    }

    /**
     * @param info where to say what went wrong, or null
     *
     * <p>A blank pane used to be the outcome of three different things -- a failed
     * fetch, a decode that returned null, and a camera that simply had no picture --
     * and none of them said so. An operator reported a black pane on Georgia and
     * there was nothing in the log to tell the three apart, because a fetch that
     * succeeds is silent and a null decode returned without a word. The panel says
     * what it is not showing everywhere else; this is the one place it did not.
     */
    private void fetchInto(String url, final ImageView into, final String key,
            final TextView info) {
        if (key != null) {
            into.setTag(key);
            final Bitmap had = frames.get(key);
            if (had != null)
                into.setImageBitmap(had);   // something to look at while we fetch
        }
        // Say it is working. A camera can take five seconds to answer -- SAV-0038
        // on the Georgia network does -- and a pane that sits blank for five
        // seconds reads as broken, which is how a slow camera got reported as a
        // black screen. The line is removed the moment the picture lands.
        final CharSequence base = info == null ? null : info.getText();
        say(info, "\nFetching the latest picture\u2026");
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
                        if (bm == null) {
                            Log.w(TAG, "image did not decode for " + key + " ("
                                    + body.length + " bytes)");
                            restore(info, base);
                            say(info, "\nThe agency sent something this could not "
                                    + "display");
                            return;
                        }
                        if (key != null)
                            frames.put(key, bm);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (key != null && !key.equals(into.getTag()))
                                    return;     // the operator moved on
                                into.setImageBitmap(bm);
                                if (info != null && base != null)
                                    info.setText(base);
                            }
                        });
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                Log.w(TAG, "image fetch failed: " + error);
                restore(info, base);
                say(info, "\nCould not reach this camera's picture");
            }
        });
    }

    /** Put the detail text back as it was, dropping any progress line. */
    private void restore(final TextView info, final CharSequence base) {
        if (info == null || base == null)
            return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                info.setText(base);
            }
        });
    }

    /** Append a line to the detail text, on the main thread, if there is one. */
    private void say(final TextView info, final String line) {
        if (info == null)
            return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                info.append(line);
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(mapView.getContext(), s, Toast.LENGTH_SHORT).show();
    }

    // ---- list -------------------------------------------------------------

    private final class CameraAdapter extends BaseAdapter {

        /** Each entry is either a heading {@link String} or a {@link Camera}. */
        private List<Object> rows = new ArrayList<>();
        private GeoPoint from;

        private static final int TYPE_HEADING = 0;
        private static final int TYPE_CAMERA = 1;

        /**
         * Two sections in one scroller: the operator's own cameras, then everything
         * the filters matched.
         *
         * <p>Headings only appear when there is something above <em>and</em> below to
         * separate. An unpinned list is exactly what it was before this existed.
         */
        void setSections(List<Camera> favorites, List<Camera> rest, GeoPoint from,
                String state, String query) {
            final List<Object> next = new ArrayList<>(
                    favorites.size() + rest.size() + 2);
            if (!favorites.isEmpty()) {
                next.add(String.format(Locale.US, "Favorites (%,d)",
                        favorites.size()));
                next.addAll(favorites);
                // Name the second section for what is actually in it. With a search
                // running it spans every state, so calling it "CA cameras" would be
                // a lie the operator can see on the rows themselves.
                next.add(query.isEmpty()
                        ? String.format(Locale.US, "%s cameras (%,d)", state,
                                rest.size())
                        : String.format(Locale.US, "Search results (%,d)",
                                rest.size()));
            }
            next.addAll(rest);
            this.rows = next;
            this.from = from;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int i) {
            return rows.get(i) instanceof Camera ? TYPE_CAMERA : TYPE_HEADING;
        }

        /** A heading is not a camera; it must not highlight or open anything. */
        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int i) {
            return rows.get(i) instanceof Camera;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (getItemViewType(position) == TYPE_HEADING) {
                View h = convertView;
                if (h == null)
                    h = PluginLayoutInflater.inflate(pluginContext,
                            R.layout.list_section, null);
                ((TextView) h.findViewById(R.id.section))
                        .setText((String) rows.get(position));
                return h;
            }
            View v = convertView;
            if (v == null)
                v = PluginLayoutInflater.inflate(pluginContext,
                        R.layout.camera_row, null);
            final Camera c = (Camera) rows.get(position);

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
                        c.metersFrom(from.getLatitude(), from.getLongitude())));
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

            final TextView star = v.findViewById(R.id.star);
            styleStar(star, c.id);
            star.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View b) {
                    toggleFavorite(c, (TextView) b);
                }
            });

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
