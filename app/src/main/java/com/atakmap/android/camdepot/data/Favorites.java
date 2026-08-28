package com.atakmap.android.camdepot.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmap.coremap.log.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The operator's marked cameras, by camera id.
 *
 * <p><strong>Deliberately not keyed to a state, at any level.</strong> Every bug on
 * the morning of 2026-08-27 came from the plugin treating the state in the panel's
 * dropdown as the only one that mattered, and favorites are cross-state by their
 * nature: the whole point is a list that holds a lookout in California next to a DOT
 * camera in Oregon. So this is one flat set of ids, the panel reads it against
 * {@link CameraStore#everywhere()}, and nothing here can be scoped by accident.
 *
 * <p>Ids come from ALERT West and are unique across the whole 13,695-camera catalog,
 * so an id alone is enough to name a camera anywhere in it.
 *
 * <p>Persisted in ATAK's own default preferences, on the <em>MapView</em> context.
 * The plugin context is not a real Android app context for this purpose and its
 * preferences do not survive the plugin being reloaded.
 */
public final class Favorites {

    private static final String TAG = "CamDepotFavorites";
    private static final String PREF = "camdepot_favorites";

    private final SharedPreferences prefs;
    private final Set<String> ids = new HashSet<>();

    /** @param uiContext the MapView context, never the plugin context */
    public Favorites(Context uiContext) {
        SharedPreferences p = null;
        try {
            p = PreferenceManager.getDefaultSharedPreferences(uiContext);
            // getStringSet hands back the live instance it is caching. Copying is not
            // tidiness: mutating it corrupts the in-memory preference and the change
            // is then never written, because SharedPreferences sees no difference.
            final Set<String> stored = p.getStringSet(PREF, null);
            if (stored != null)
                ids.addAll(stored);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not read the favorites list", e);
        }
        this.prefs = p;
    }

    public boolean contains(String id) {
        return id != null && ids.contains(id);
    }

    /**
     * Add the camera if it is not marked, remove it if it is.
     *
     * @return true if the camera is a favorite afterwards
     */
    public boolean toggle(String id) {
        if (id == null || id.isEmpty())
            return false;
        final boolean now = !ids.contains(id);
        if (now)
            ids.add(id);
        else
            ids.remove(id);
        save();
        return now;
    }

    public int size() {
        return ids.size();
    }

    public boolean isEmpty() {
        return ids.isEmpty();
    }

    public Set<String> ids() {
        return Collections.unmodifiableSet(ids);
    }

    private void save() {
        if (prefs == null)
            return;
        try {
            prefs.edit().putStringSet(PREF, new HashSet<>(ids)).apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "could not save the favorites list", e);
        }
    }
}
