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
    static final String DEFAULT_BASE_URL = "https://mapdepot.takwerx.org/camdepot";

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane pane;
    /** Second pane holding a single camera's picture, so the map stays live. */
    Pane detailPane;
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
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                }).setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        if (uiService == null)
            return;
        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
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
                    if (detailPane != null && uiService.isPaneVisible(detailPane))
                        uiService.closePane(detailPane);
                    detailPane = new PaneBuilder(v)
                            .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                            .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.45D)
                            .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.85D)
                            .build();
                    uiService.showPane(detailPane, null);
                }

                @Override
                public void hideDetailPane() {
                    if (detailPane != null && uiService.isPaneVisible(detailPane))
                        uiService.closePane(detailPane);
                }
            });
            pane = new PaneBuilder(content.getView())
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.45D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.85D)
                    .build();
        }
        if (!uiService.isPaneVisible(pane))
            uiService.showPane(pane, null);
    }
}
