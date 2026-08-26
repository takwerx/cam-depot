package com.atakmap.android.camdepot.net;

import android.os.Handler;
import android.os.Looper;

import com.atakmap.coremap.log.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;

/**
 * Small HTTPS GET client: bounded threads, bounded time, bounded response size.
 *
 * <p>Adapted from the Weather plugin's {@code net.Http}, with two changes CamDepot
 * needs. It returns {@code byte[]} rather than a String, because most of what we
 * fetch is a JPEG; and it gunzips {@code .gz} payloads itself.
 *
 * <p>That second part is deliberate. The catalog shards are stored gzipped as
 * <em>content</em>, not with {@code Content-Encoding: gzip} — so the transport will
 * not unwrap them and we must. Asking for {@code Accept-Encoding: identity} keeps
 * any CDN from adding a second, transport-level layer on top of the one we expect.
 *
 * <p>Callbacks land on the main thread, so callers can touch views directly.
 * Anonymous classes rather than lambdas throughout — the ATAK SDK documents lambdas
 * breaking under release proguard, and this code ships in release builds.
 */
public final class Http {

    private static final String TAG = "CamDepotHttp";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    /** The largest shard is ~160 KB and a camera still is under a megabyte. */
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CONCURRENT = 4;

    public interface Callback {
        void onSuccess(byte[] body);

        /** @param error already phrased for the operator, not a stack trace */
        void onFailure(String error);
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
            MAX_CONCURRENT, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    final Thread t = new Thread(r, "camdepot-http");
                    t.setDaemon(true);
                    return t;
                }
            });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Http() {
    }

    /** GET {@code url}; if it ends in .gz the body is gunzipped before delivery. */
    public static void get(final String url, final Callback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    deliver(callback, request(url), null);
                } catch (IOException e) {
                    Log.w(TAG, "GET failed: " + url, e);
                    deliver(callback, null, describe(e));
                } catch (RuntimeException e) {
                    // Never let a plugin thread take ATAK down.
                    Log.e(TAG, "GET failed hard: " + url, e);
                    deliver(callback, null, "request failed");
                }
            }
        });
    }

    private static byte[] request(String url) throws IOException {
        final URL parsed = new URL(url);
        if (!"https".equalsIgnoreCase(parsed.getProtocol()))
            throw new IOException("refusing a non-https request");

        HttpsURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpsURLConnection) parsed.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "CamDepot-ATAK-plugin");
            // See the class comment: our .gz files are content, not encoding.
            conn.setRequestProperty("Accept-Encoding", "identity");

            final int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK)
                throw new IOException("server returned HTTP " + status);

            in = conn.getInputStream();
            if (isGzip(url))
                in = new GZIPInputStream(in);
            return read(in);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Already have the body or the failure.
                }
            }
            if (conn != null)
                conn.disconnect();
        }
    }

    /**
     * Whether this URL names a gzipped file.
     *
     * <p>Tests the <em>path</em>, not the whole URL. Checking {@code endsWith(".gz")}
     * on the full string worked until cache-busting appended {@code ?v=...}, at which
     * point nothing was decompressed and every shard arrived as raw gzip that the JSON
     * parser rejected. A query string does not change what the file is.
     */
    private static boolean isGzip(String url) {
        final int q = url.indexOf('?');
        return (q < 0 ? url : url.substring(0, q)).endsWith(".gz");
    }

    private static byte[] read(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        final byte[] buf = new byte[16384];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BYTES)
                throw new IOException("response larger than "
                        + (MAX_BYTES / (1024 * 1024)) + " MB");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void deliver(final Callback callback, final byte[] body,
            final String error) {
        if (callback == null)
            return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (error == null)
                    callback.onSuccess(body);
                else
                    callback.onFailure(error);
            }
        });
    }

    private static String describe(IOException e) {
        final String message = e.getMessage();
        if (e instanceof java.net.SocketTimeoutException)
            return "timed out";
        if (e instanceof java.net.UnknownHostException)
            return "no route to the catalog host";
        if (e instanceof javax.net.ssl.SSLException)
            return "TLS failed";
        return message == null ? "network error" : message;
    }
}
