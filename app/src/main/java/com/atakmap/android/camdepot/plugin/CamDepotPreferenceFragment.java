package com.atakmap.android.camdepot.plugin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.preference.Preference;

import com.atakmap.android.preference.PluginPreferenceFragment;
import com.atakmap.android.util.PdfHelper;
import com.atakmap.coremap.filesystem.FileSystemUtils;

import java.io.File;

/**
 * The plugin's entry under ATAK's Tool Preferences, and the only way to reach the
 * user manual.
 *
 * <p>The manual is built by {@code gradle/typst.gradle} into
 * {@code assets/usermanual.pdf}, but an asset is not reachable by anyone: ATAK
 * surfaces a plugin's documentation through this screen, so without it the PDF
 * ships inside the APK and no operator can open it. That shipped once in this
 * repo, undetected, because the PDF genuinely was in the APK.
 *
 * <p>Nothing else lives on this screen on purpose. The catalog address is fixed,
 * and every other choice -- state, providers, zoom threshold, favorites -- is made
 * on the panel, next to the thing it affects.
 */
public class CamDepotPreferenceFragment extends PluginPreferenceFragment {

    private static final String USER_GUIDE = "usermanual.pdf";

    /**
     * Where the PDF is extracted to before a viewer is handed it. Under the
     * plugin's own folder in ATAK's tree, named for what it is rather than for the
     * asset, because this is the name the operator sees in a file picker.
     */
    private static final String USER_GUIDE_PATH = FileSystemUtils.getRoot()
            + File.separator + "tools" + File.separator + "camdepot"
            + File.separator + "Cam Depot User Guide.pdf";

    private static Context pluginContext;

    public CamDepotPreferenceFragment() {
        super(pluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public CamDepotPreferenceFragment(Context context) {
        super(context, R.xml.preferences);
        pluginContext = context;
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tool Preferences", "Cam Depot");
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Preference manual = findPreference("manual");
        if (manual == null)
            return;
        manual.setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        PdfHelper.extractAndShow(pluginContext, getActivity(),
                                USER_GUIDE, USER_GUIDE_PATH, true);
                        return true;
                    }
                });
    }
}
