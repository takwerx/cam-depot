package com.atakmap.android.camdepot.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code catalog.json} — what the plugin reads first.
 *
 * <p>Providers and states are read from the file rather than compiled in. The
 * publisher can add a state or a provider without a plugin release, and the panel
 * shows whatever is actually there. PLAN-CamDepot-v0.1.md makes this a rule: the
 * plugin must never assume a single upstream or a fixed provider list.
 */
public final class Catalog {

    public static final class State {
        public final String code;
        public final int cameras;
        public final int fire;
        public final List<String> counties;

        State(String code, int cameras, int fire, List<String> counties) {
            this.code = code;
            this.cameras = cameras;
            this.fire = fire;
            this.counties = counties;
        }
    }

    public static final class Provider {
        public final String id;
        public final int cameras;
        public final boolean fire;

        Provider(String id, int cameras, boolean fire) {
            this.id = id;
            this.cameras = cameras;
            this.fire = fire;
        }
    }

    public final int format;
    public final String generated;
    public final String imageBase;
    public final int totalCameras;
    public final List<State> states = new ArrayList<>();
    public final List<Provider> providers = new ArrayList<>();

    /** The format this build understands. A newer catalog is refused, not guessed at. */
    public static final int SUPPORTED_FORMAT = 1;

    public Catalog(JSONObject o) throws JSONException {
        format = o.optInt("format", 0);
        generated = o.optString("generated", "");
        imageBase = o.optString("image_base", "");
        totalCameras = o.optJSONObject("counts") == null ? 0
                : o.getJSONObject("counts").optInt("cameras", 0);

        final JSONArray st = o.optJSONArray("states");
        for (int i = 0; st != null && i < st.length(); i++) {
            final JSONObject s = st.getJSONObject(i);
            final List<String> counties = new ArrayList<>();
            final JSONArray ca = s.optJSONArray("counties");
            for (int j = 0; ca != null && j < ca.length(); j++)
                counties.add(ca.getString(j));
            states.add(new State(s.optString("st"), s.optInt("cameras"),
                    s.optInt("fire"), counties));
        }

        final JSONArray pr = o.optJSONArray("providers");
        for (int i = 0; pr != null && i < pr.length(); i++) {
            final JSONObject p = pr.getJSONObject(i);
            providers.add(new Provider(p.optString("id"), p.optInt("cameras"),
                    p.optInt("fire") == 1));
        }
    }

    public State state(String code) {
        for (State s : states) {
            if (s.code.equalsIgnoreCase(code))
                return s;
        }
        return null;
    }
}
