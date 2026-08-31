package com.atakmap.android.camdepot.plugin;

import android.content.Context;
import android.content.SharedPreferences;

import android.preference.PreferenceManager;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.camdepot.ui.CamDepotPane;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Cam Depot — public camera networks on the map, browsed the way Map Depot browses
 * map packages.
 *
 * <p>The catalog URL is a preference rather than a constant. Map Depot learned this
 * the hard way: the public R2 host is expected to move to a custom domain, and a
 * hostname baked into a build would mean a plugin release to follow it.
 */
public class CamDepot implements IPlugin {

    private static final String TAG = "CamDepot";

    static final String PREF_BASE_URL = "camdepot_base_url";
    /** Key for the plugin's entry in ATAK's Tool Preferences. */
    private static final String PREFS_KEY = "camdepotPreference";
    static final String DEFAULT_BASE_URL = "https://mapdepot.takwerx.org/camdepot";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane pane;
    /** Second pane holding a single camera's picture, so the map stays live. */
    Pane detailPane;
    /**
     * True only while one detail pane is being swapped for another.
     *
     * <p>Closing the old one fires {@code onPaneClose}, which would otherwise put the
     * camera list back for the instant before the new picture opens over it.
     */
    private boolean swappingDetail;
    /**
     * Set when the plugin itself is handing the slot to ATAK — the video player.
     * Cleared by the close it was set for, and by opening any new detail pane, so it
     * can never leave a later Close with nothing to go back to.
     *
     * <p>The list does NOT come back when the video is closed, and that is a limit
     * rather than a choice. ATAK's player is not registered as a dropdown this plugin
     * can listen to: {@code DropDownManager.getTopDropDownKey()} reads null for the
     * whole time it is playing, sampled every 700 ms on device, so there is no close
     * to hook. Reopening the list on a timer instead would cover the video, and
     * opening it before the launch stopped the video playing at all. So back from the
     * video lands on the map and the toolbar icon reopens the list.
     */
    private boolean keepListClosedOnce;
    CamDepotPane content;

    public CamDepot(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        uiService = serviceController.getService(IHostUIService.class);

        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        // ic_toolbar, not ic_launcher. See the note on the
                        // ToolPreference icon below: this one sits on ATAK's dark
                        // toolbar and wants the bare white glyph.
                        pluginContext.getResources().getDrawable(R.drawable.ic_toolbar),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        togglePane();
                    }
                }).setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        if (uiService == null)
            return;
        uiService.addToolbarItem(toolbarItem);
        registerPreferences();
    }

    /**
     * Put the plugin in ATAK's Tool Preferences, which is the only way an operator
     * can reach the user manual.
     *
     * <p>The manual is compiled into {@code assets/usermanual.pdf}, and an asset is
     * not reachable by anyone -- without this entry it ships inside the APK with no
     * way to open it. That has already happened once in this repo, undetected,
     * because the PDF genuinely was in the APK.
     *
     * <p>Guarded rather than assumed: a build that does not expose
     * {@code ToolsPreferenceFragment} should cost the manual, not the plugin.
     */
    private void registerPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment.register(
                    new com.atakmap.app.preferences.ToolsPreferenceFragment
                            .ToolPreference(
                            pluginContext.getString(R.string.app_name),
                            pluginContext.getString(R.string.prefs_summary),
                            PREFS_KEY,
                            // ic_toolbar, not ic_launcher.
                            //
                            // The SDK template ships one icon and wires it to three
                            // places: android:icon in the manifest, the toolbar
                            // button, and this row. A bare white glyph on
                            // transparency is right for the two that sit on ATAK's
                            // dark UI and INVISIBLE for android:icon, which Android
                            // draws on light backgrounds -- the app list, Settings,
                            // and the My Files browser a user reaches the extracted
                            // manual through. It renders as a blank square there.
                            //
                            // Inverting the single icon just moves the problem: the
                            // toolbar button disappears instead. So there are two.
                            // ic_launcher is the glyph on a #121212 tile for
                            // Android; ic_toolbar is the bare glyph for ATAK.
                            // Measured on the built APK: 16% opaque and 0 non-white
                            // pixels before, 99% and 60,146 after. Found on Map
                            // Depot by the parallel session, 2026-08-31.
                            pluginContext.getResources().getDrawable(
                                    R.drawable.ic_toolbar),
                            new CamDepotPreferenceFragment(pluginContext)));
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not register preferences: " + notThisBuild);
        }
    }

    private void unregisterPreferences() {
        try {
            com.atakmap.app.preferences.ToolsPreferenceFragment
                    .unregister(PREFS_KEY);
        } catch (LinkageError | RuntimeException notThisBuild) {
            Log.w(TAG, "could not unregister preferences: " + notThisBuild);
        }
    }

    @Override
    public void onStop() {
        unregisterPreferences();
        if (uiService == null)
            return;
        uiService.removeToolbarItem(toolbarItem);
        if (content != null) {
            content.dispose();
            content = null;
            pane = null;
        }
    }

    private String baseUrl() {
        try {
            final MapView mv = MapView.getMapView();
            if (mv != null) {
                final SharedPreferences p = PreferenceManager
                        .getDefaultSharedPreferences(mv.getContext());
                final String v = p.getString(PREF_BASE_URL, DEFAULT_BASE_URL);
                if (v != null && !v.trim().isEmpty())
                    return v.trim();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read the catalog URL preference", e);
        }
        return DEFAULT_BASE_URL;
    }

    /** One tap opens the camera list, the next clears it off the map. */
    private void togglePane() {
        if (pane != null && uiService != null && uiService.isPaneVisible(pane)) {
            uiService.closePane(pane);
            return;
        }
        showPane();
    }

    private void showPane() {
        final MapView mapView = MapView.getMapView();
        if (mapView == null) {
            Log.w(TAG, "no MapView yet; ignoring the toolbar tap");
            return;
        }
        if (pane == null) {
            content = new CamDepotPane(pluginContext, mapView, baseUrl());
            content.setDetailHost(new CamDepotPane.DetailHost() {
                @Override
                public void showDetailPane(android.view.View v) {
                    if (detailPane != null && uiService.isPaneVisible(detailPane)) {
                        swappingDetail = true;
                        uiService.closePane(detailPane);
                    }
                    final Pane opened = new PaneBuilder(v)
                            .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                            .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                            .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                            .build();
                    detailPane = opened;
                    keepListClosedOnce = false;
                    // Both panes live in the same slot, so opening a camera REPLACES
                    // the camera list rather than sitting beside it. Closing the
                    // picture therefore has to put the list back, or the plugin simply
                    // disappears -- which is what the back key did: it closed the
                    // detail pane and left the operator on a bare map with no way back
                    // except the toolbar. Listening for the close rather than doing
                    // this in the Close button's handler is deliberate; the back key
                    // never goes through that button.
                    uiService.showPane(opened,
                            new IHostUIService.IPaneLifecycleListener() {
                                @Override
                                public void onPaneVisible(boolean visible) {
                                }

                                @Override
                                public void onPaneClose() {
                                    if (swappingDetail || detailPane != opened)
                                        return;     // a newer picture took its place
                                    if (keepListClosedOnce) {
                                        keepListClosedOnce = false;
                                        return;     // the video player has the slot
                                    }
                                    if (pane != null && !uiService.isPaneVisible(pane))
                                        uiService.showPane(pane, null);
                                }
                            });
                    swappingDetail = false;
                }

                @Override
                public void keepListClosedOnce() {
                    keepListClosedOnce = true;
                }

                @Override
                public void hideDetailPane() {
                    if (detailPane != null && uiService.isPaneVisible(detailPane))
                        uiService.closePane(detailPane);
                }
            });
            // Default slot, like every other ATAK pane. Pane.Location.Left was tried
            // to keep the list out of the video player's way; ATAK ignores it and
            // opens on the right regardless, and the left is not where an operator
            // expects a tool pane to be.
            pane = new PaneBuilder(content.getView())
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    // 0.5 / 0.5, the same as PLSS, Map Depot and Traffic.
                    //
                    // This was 0.45 / 0.85, which looks fine in landscape because the
                    // WIDTH ratio governs there. In portrait the height ratio governs
                    // and the panel took 85% of the screen, leaving a sliver of map.
                    // The other plugins never showed it because they were 0.5 / 0.5
                    // all along.
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }
}
