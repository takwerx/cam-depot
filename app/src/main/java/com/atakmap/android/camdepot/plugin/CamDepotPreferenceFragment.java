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

    /**
     * A number that changes with every release, for PdfHelper's cache.
     *
     * <p>The five-argument {@code extractAndShow} reads the APK's
     * {@code versionCode} and re-extracts the PDF only when it differs from the one
     * it stored under {@code pdfhelper.document-<package>}. That is sound where the
     * version code moves. Ours does not:
     *
     * <p>{@code getVersionCode()} in {@code app/build.gradle} derives from git, and
     * tak.gov builds from a source zip with no {@code .git} in it, so it falls back
     * to <strong>1 on every signed release</strong> -- measured on the 0.8 zip, a
     * clean extract gives {@code versionCode='1'} where a build in the git tree
     * gives a timestamp. So the stored value never differs, the PDF is never
     * re-extracted, and the first manual a user ever opens is the one they keep.
     * The plugin manager says 0.8 while the manual says 0.5, and nothing on the
     * device can be sideloaded to fix it.
     *
     * <p>{@code versionName} does NOT have this problem -- it carries
     * PLUGIN_VERSION and is correct in a signed build ("0.8 () - [5.7.0]"). So the
     * six-argument overload is handed a number derived from that instead, and the
     * manual updates when the version does. Found by the Map Depot session on
     * 2026-08-31; both plugins are affected and no other takwerx plugin uses
     * PdfHelper.
     */
    private static long manualVersion() {
        try {
            final String name = pluginContext.getString(R.string.versionName);
            // hashCode, not a parse: versionName is "0.8 () - [5.7.0]", so the ATAK
            // target is in there too and a rebuild for a different target should
            // also refresh the manual. Sign-extended into a long so a negative hash
            // stays distinct.
            return name.hashCode() & 0xFFFFFFFFL;
        } catch (RuntimeException noResource) {
            // Never let the manual fail to open over its own cache key.
            return System.currentTimeMillis();
        }
    }

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
                                USER_GUIDE, manualVersion(), USER_GUIDE_PATH,
                                true);
                        return true;
                    }
                });
    }
}
