package com.atakmap.android.camdepot.data;

import com.atakmap.android.camdepot.model.Camera;
import com.atakmap.android.camdepot.model.Catalog;
import com.atakmap.android.camdepot.net.Http;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Holds the loaded cameras and knows how to refresh them.
 *
 * <p>The store is keyed by camera id and <strong>updates in place</strong>. Nothing
 * here ever removes and re-adds a camera on refresh; see {@link Camera} for why that
 * matters to the sensor cone.
 *
 * <p>Three fetches, on three different clocks, matching how the publisher shards:
 * <ul>
 *   <li>{@code static/<ST>} — once per state, cached for the session</li>
 *   <li>{@code dynamic/<ST>} — every refresh; ~26 KB for California</li>
 *   <li>{@code images/<ST>} — only when something wants a picture</li>
 * </ul>
 */
public final class CameraStore {

    private static final String TAG = "CamDepotStore";

    public interface Listener {
        void onCatalog(Catalog catalog);

        /** A state's cameras finished loading or refreshing. */
        void onCameras(String state, List<Camera> cameras);

        void onError(String message);
    }

    private final String baseUrl;
    private final Listener listener;

    /**
     * state code -> id -> camera.
     *
     * <p>Concurrent because parsing happens off the main thread: {@link Http} delivers
     * callbacks on the UI thread so callers can touch views, which is right for one
     * state and fatal for forty — parsing all 13,696 cameras there blocked input long
     * enough for Android to ANR the whole of ATAK.
     */
    private final Map<String, Map<String, Camera>> byState =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Parsing lives here, never on the looper that delivered the bytes. */
    private static final java.util.concurrent.ExecutorService PARSER =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            final Thread t = new Thread(r, "camdepot-parse");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Catalog catalog;
    /** Last change sequence seen from the live feed, per state. */
    private final Map<String, Integer> seq = new java.util.concurrent.ConcurrentHashMap<>();

    public CameraStore(String baseUrl, Listener listener) {
        // One trailing slash, however the preference was typed.
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.listener = listener;
    }

    /**
     * Cache-busting suffix for a shard URL.
     *
     * <p>R2 serves these behind Cloudflare with {@code max-age=14400}, so a plugin
     * polling every 60 s was being handed data up to <strong>four hours old</strong> —
     * the refresh ran faithfully and changed nothing. Keying the URL to the catalog's
     * publish time gives each publish its own cache entry: still cached hard, but a
     * new publish is a new key, so a refresh actually sees it.
     */
    private String bust() {
        if (catalog == null || catalog.generated == null || catalog.generated.isEmpty())
            return "";
        return "?v=" + catalog.generated.replaceAll("[^0-9]", "");
    }

    public Catalog catalog() {
        return catalog;
    }

    public String imageBase() {
        return catalog == null ? "" : catalog.imageBase;
    }

    public boolean isLoaded(String state) {
        return byState.containsKey(state);
    }

    public List<Camera> cameras(String state) {
        final Map<String, Camera> m = byState.get(state);
        if (m == null)
            return Collections.emptyList();
        return new ArrayList<>(m.values());
    }

    /** Every camera currently loaded, across all states the operator selected. */
    public List<Camera> all() {
        final List<Camera> out = new ArrayList<>();
        for (Map<String, Camera> m : byState.values())
            out.addAll(m.values());
        return out;
    }

    /**
     * Load every state's static shard so search can span the whole catalog.
     *
     * <p>Search was previously scoped to the loaded state, which meant typing a camera
     * name found nothing unless you had already guessed which state it was in — the
     * operator looked for "Moses" while on California, where there is no Moses. Nobody
     * types a name expecting it to only search where they happen to be standing.
     *
     * <p>Affordable because static data is small and immutable: all 40 states together
     * are ~376 KB gzipped, fetched once per session. Only the selected state's
     * <em>dynamic</em> half is refreshed on a timer, so this costs nothing recurring.
     */
    public void loadAllStates(final Runnable done) {
        if (catalog == null) {
            if (done != null)
                done.run();
            return;
        }
        final List<String> todo = new ArrayList<>();
        for (Catalog.State s : catalog.states) {
            if (!byState.containsKey(s.code))
                todo.add(s.code);
        }
        if (todo.isEmpty()) {
            if (done != null)
                done.run();
            return;
        }
        // Http caps concurrency at 4, so these queue rather than storm the network;
        // the parse is the part that had to move off the looper.
        final int[] left = { todo.size() };
        for (final String st : todo) {
            Http.get(baseUrl + "/static/" + st + ".json.gz" + bust(), new Http.Callback() {
                @Override
                public void onSuccess(final byte[] body) {
                    PARSER.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                parseStatic(st, text(body));
                            } catch (JSONException | UnsupportedEncodingException e) {
                                Log.w(TAG, "static parse failed for " + st, e);
                            } catch (RuntimeException e) {
                                Log.w(TAG, "static parse failed hard for " + st, e);
                            }
                            MAIN.post(new Runnable() {
                                @Override
                                public void run() {
                                    finish();
                                }
                            });
                        }
                    });
                }

                @Override
                public void onFailure(String error) {
                    Log.w(TAG, "static fetch failed for " + st + ": " + error);
                    finish();
                }

                private void finish() {
                    if (--left[0] == 0 && done != null)
                        done.run();
                }
            });
        }
    }

    /** Every camera in every state loaded so far. */
    public List<Camera> everywhere() {
        final List<Camera> out = new ArrayList<>();
        for (Map<String, Camera> m : byState.values())
            out.addAll(m.values());
        return out;
    }

    public void forget(String state) {
        byState.remove(state);
    }

    // ---- loading ----------------------------------------------------------

    public void loadCatalog() {
        // The catalog carries the publish time every other URL is keyed to, so it is
        // the one thing that must not be served stale.
        Http.get(baseUrl + "/catalog.json?t=" + (System.currentTimeMillis() / 30000L),
                new Http.Callback() {
            @Override
            public void onSuccess(byte[] body) {
                try {
                    final Catalog c = new Catalog(new JSONObject(text(body)));
                    if (c.format > Catalog.SUPPORTED_FORMAT) {
                        // Refuse rather than misread: a newer publisher may have
                        // changed a shard's shape, and half-parsed cameras on a
                        // map are worse than an honest error.
                        listener.onError("catalog format " + c.format
                                + " is newer than this plugin understands");
                        return;
                    }
                    catalog = c;
                    listener.onCatalog(c);
                } catch (JSONException | UnsupportedEncodingException e) {
                    Log.w(TAG, "catalog parse failed", e);
                    listener.onError("catalog was not readable");
                }
            }

            @Override
            public void onFailure(String error) {
                listener.onError("catalog: " + error);
            }
        });
    }

    /** Fetch a state's static cameras, then immediately its dynamic half. */
    public void loadState(final String state) {
        if (byState.containsKey(state)) {
            refreshState(state);
            return;
        }
        Http.get(baseUrl + "/static/" + state + ".json.gz" + bust(), new Http.Callback() {
            @Override
            public void onSuccess(byte[] body) {
                PARSER.execute(new Runnable() {
                    @Override
                    public void run() {
                        int n = -1;
                        try {
                            n = parseStatic(state, text(body));
                        } catch (JSONException | UnsupportedEncodingException e) {
                            Log.w(TAG, "static parse failed for " + state, e);
                        }
                        final int count = n;
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                if (count < 0) {
                                    listener.onError(state
                                            + ": camera list was not readable");
                                    return;
                                }
                                Log.d(TAG, "loaded " + count + " cameras for " + state);
                                refreshState(state);
                            }
                        });
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                listener.onError(state + ": " + error);
            }
        });
    }

    private int parseStatic(String state, String json) throws JSONException {
        final JSONArray a = new JSONArray(json);
        final Map<String, Camera> m = new LinkedHashMap<>(a.length() * 2);
        for (int i = 0; i < a.length(); i++) {
            final JSONObject o = a.getJSONObject(i);
            final String id = o.optString("id");
            m.put(id, new Camera(id,
                    o.optString("n"), o.optString("h"),
                    o.optDouble("lat", Double.NaN),
                    o.optDouble("lon", Double.NaN),
                    o.optString("co"), o.optString("st"),
                    o.optString("pr"), o.optString("sp"),
                    o.optInt("ptz") == 1, o.optInt("fire") == 1,
                    o.optString("stream"), o.optString("still")));
        }
        byState.put(state, m);
        return m.size();
    }

    /** Re-read pan/tilt/fov/offline for an already-loaded state. */
    public void refreshState(final String state) {
        refreshState(state, true);
    }

    /**
     * @param live try the live feed first; false forces the R2 shard
     *
     * <p>The fallback is not decoration. Pan reaches the plugin through this call and
     * nowhere else, so a dynamic fetch that fails takes every bearing with it: every
     * camera reads pan=NaN, hasFov() is false, and "Show bearing" does nothing on any
     * camera in the catalog. That is indistinguishable from the FOV being broken, and
     * it is what a plain-http dynamic_base did -- Http refuses a non-https request, so
     * the call died before it left the device and nothing said so.
     *
     * <p>So a live-feed failure now falls back to the R2 shard: stale bearings beat no
     * bearings, and the log says which one was used.
     */
    public void refreshState(final String state, final boolean live) {
        final Map<String, Camera> m = byState.get(state);
        if (m == null)
            return;
        // Live feed when the catalog names one; the R2 shard otherwise. The shard is
        // only as fresh as the last publisher run, so a PTZ camera's bearing sits
        // still on the map however faithfully this timer fires.
        final boolean useLive = live && catalog != null
                && !catalog.dynamicBase.isEmpty();
        // Ask only for what changed. A camera moves rarely, so nearly every call
        // comes back "nothing" in 22 bytes -- which is what lets this run every few
        // seconds instead of every minute. Detection is still bounded by ALERT (their
        // images only change every ~15 s); this removes the wait between the server
        // knowing and the map knowing, which is the part an operator actually sees.
        final Integer since = seq.get(state);
        final String url = useLive
                ? catalog.dynamicBase + "?st=" + state
                        + (since != null ? "&since=" + since : "&since=0")
                : baseUrl + "/dynamic/" + state + ".json.gz" + bust();
        Http.get(url, new Http.Callback() {
            @Override
            public void onSuccess(final byte[] body) {
                try {
                    // Live feed with ?since= answers {seq, cams}; the R2 shard is a
                    // bare array.
                    final String raw = text(body);
                    JSONArray a;
                    if (raw.startsWith("{")) {
                        final JSONObject env = new JSONObject(raw);
                        seq.put(state, env.optInt("seq", 0));
                        a = env.optJSONArray("cams");
                        if (a == null)
                            a = new JSONArray();
                    } else {
                        a = new JSONArray(raw);
                    }
                    if (a.length() == 0)
                        return;                 // nothing moved; leave the map alone
                    int hit = 0;
                    for (int i = 0; i < a.length(); i++) {
                        final JSONObject o = a.getJSONObject(i);
                        final Camera c = m.get(o.optString("id"));
                        if (c == null)
                            continue;       // publisher added a camera mid-session
                        c.pan = o.isNull("p") ? Double.NaN : o.optDouble("p", Double.NaN);
                        c.tilt = o.isNull("t") ? Double.NaN : o.optDouble("t", Double.NaN);
                        c.fov = o.isNull("f") ? Double.NaN : o.optDouble("f", Double.NaN);
                        c.offline = o.optInt("off") == 1;
                        hit++;
                    }
                    Log.d(TAG, "refreshed " + hit + "/" + m.size() + " in " + state
                            + (useLive ? " (live)" : " (shard)"));
                    listener.onCameras(state, new ArrayList<>(m.values()));
                } catch (JSONException | UnsupportedEncodingException e) {
                    Log.w(TAG, "dynamic parse failed for " + state, e);
                    listener.onError(state + ": status was not readable");
                }
            }

            @Override
            public void onFailure(String error) {
                if (useLive) {
                    Log.w(TAG, "live feed failed (" + error
                            + "); falling back to the published shard");
                    refreshState(state, false);
                    return;
                }
                Log.w(TAG, "dynamic refresh failed for " + state + ": " + error);
                listener.onError(state + ": " + error);
            }
        });
    }

    /**
     * Fetch the image filenames for a state. Separate because they are ~60% of a
     * naive refresh payload and a map full of markers needs none of them.
     */
    public void loadImages(final String state, final Runnable done) {
        final Map<String, Camera> m = byState.get(state);
        if (m == null)
            return;
        Http.get(baseUrl + "/images/" + state + ".json.gz" + bust(), new Http.Callback() {
            @Override
            public void onSuccess(byte[] body) {
                try {
                    final JSONObject o = new JSONObject(text(body));
                    for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                        final String id = it.next();
                        final Camera c = m.get(id);
                        if (c != null)
                            c.image = o.optString(id, "");
                    }
                } catch (JSONException | UnsupportedEncodingException e) {
                    Log.w(TAG, "images parse failed for " + state, e);
                }
                if (done != null)
                    done.run();
            }

            @Override
            public void onFailure(String error) {
                Log.w(TAG, "images fetch failed for " + state + ": " + error);
                if (done != null)
                    done.run();
            }
        });
    }

    /**
     * Build the URL for a camera's current still.
     *
     * <p>The date path is derived from the unix timestamp inside the filename —
     * {@code Keller_Peak_1_1787690741_9133.jpg} becomes
     * {@code .../2044/2026/08/25/Keller_Peak_1_1787690741_9133.jpg}. There is no
     * stable "current.jpg" on their CDN; every variant was probed and 404s.
     *
     * @return null when the camera has no image filename loaded yet
     */
    public String imageUrl(Camera c) {
        if (c == null)
            return null;
        // The agency's own frame wins when there is one. Cache-busted, because these
        // sit behind a CDN that will otherwise hand back whatever it served last.
        if (!c.still.isEmpty())
            return c.still + (c.still.indexOf('?') < 0 ? "?_=" : "&_=")
                    + (System.currentTimeMillis() / 1000L);
        if (c.image == null || c.image.isEmpty() || catalog == null)
            return null;
        final long ts = timestampIn(c.image);
        if (ts <= 0)
            return null;
        final java.util.Calendar cal = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(ts * 1000L);
        return String.format(Locale.US, "%s/%s/%04d/%02d/%02d/%s",
                catalog.imageBase, c.id,
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
                c.image);
    }

    /**
     * Pull the unix timestamp out of an image filename.
     *
     * <p>Two formats are in use and they differ by one trailing token:
     *
     * <pre>
     *   fire      Mt_Tamalpais_East_1787725372_1707.jpg
     *   DOT/FAA   SR-51_NB_1300_S_of_Bell_Rd_1787725136_6979_s.jpg
     * </pre>
     *
     * <p>Taking the second-from-last token works for the first and picks up a random
     * suffix for the second, which put every DOT and FAA image under the wrong date
     * and made 11,535 cameras' pictures silently 404. So scan for a ten-digit token
     * instead of counting from the end — that is what a unix timestamp looks like and
     * it does not care how many suffixes get appended later.
     */
    private static long timestampIn(String filename) {
        final String[] parts = filename.split("[._]");
        for (int i = parts.length - 1; i >= 0; i--) {
            final String p = parts[i];
            if (p.length() == 10 && isDigits(p)) {
                try {
                    return Long.parseLong(p);
                } catch (NumberFormatException ignored) {
                    // keep looking
                }
            }
        }
        return -1;
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i)))
                return false;
        }
        return true;
    }

    private static String text(byte[] b) throws UnsupportedEncodingException {
        return new String(b, "UTF-8");
    }
}
