package com.example.selfpilot.account;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Realtime Database over REST.
 *
 * Used for the live session channel: the phone that is running Focus or Exam mode publishes
 * its state, and any other signed-in device of the same account sees it immediately through
 * the streaming endpoint. Nothing here can start, stop or unlock a mode - it is a status
 * channel only.
 */
public final class RtDatabase {

    private static final String TAG = "SELF_PILOT_RTDB";
    private static final String STREAM_ACCEPT = "text/event-stream";

    private RtDatabase() {
    }

    public interface StreamListener {
        /** @param data raw JSON of the new value, or "null" when the node was removed. */
        void onValue(String data);

        void onClosed(String reason);
    }

    /** Handle that stops a running stream. */
    public interface Stream {
        void close();
    }

    // ------------------------------------------------------------------------------------
    // Simple reads / writes
    // ------------------------------------------------------------------------------------

    static String url(String path, String token) {
        return CloudConfig.RTDB_BASE + "/" + path + ".json?auth=" + encode(token);
    }

    static boolean put(String path, String json, String token) {
        Http.Response response = Http.sendRaw("PUT", url(path, token), json, "application/json");
        return response.isOk();
    }

    static String get(String path, String token) {
        Http.Response response = Http.get(url(path, token), null);
        if (!response.isOk()) return null;
        return response.body;
    }

    static boolean delete(String path, String token) {
        Http.Response response = Http.sendRaw("DELETE", url(path, token), null, null);
        return response.isOk();
    }

    // ------------------------------------------------------------------------------------
    // Streaming (server-sent events)
    // ------------------------------------------------------------------------------------

    /**
     * Opens a live listener. The connection is kept open by the server and pushes every change
     * as an SSE frame. Call {@link Stream#close()} on the returned handle to stop it.
     */
    static Stream stream(final String path, final String token, final StreamListener listener) {
        final Object lock = new Object();
        final boolean[] closed = {false};
        final HttpURLConnection[] holder = new HttpURLConnection[1];

        Thread thread = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String requestUrl = url(path, token);
                conn = (HttpURLConnection) new URL(requestUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", STREAM_ACCEPT);
                conn.setReadTimeout(0); // streaming: never time out
                conn.setConnectTimeout(15000);
                synchronized (lock) {
                    holder[0] = conn;
                }

                int code = conn.getResponseCode();
                if (code != 200) {
                    if (listener != null) listener.onClosed("HTTP " + code);
                    return;
                }

                InputStream in = conn.getInputStream();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8));

                String event = null;
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (lock) {
                        if (closed[0]) break;
                    }
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            if ("put".equals(event) || "patch".equals(event)) {
                                if (listener != null) listener.onValue(data.toString());
                            }
                            data.setLength(0);
                        }
                        event = null;
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring(5).trim());
                    }
                }
                if (listener != null) listener.onClosed("stream ended");
            } catch (Exception e) {
                if (listener != null) listener.onClosed(String.valueOf(e.getMessage()));
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Exception ignored) {
                    }
                }
                Log.i(TAG, "[RTDB] stream closed");
            }
        }, "selfpilot-rtdb-stream");

        thread.setDaemon(true);
        thread.start();

        return () -> {
            synchronized (lock) {
                closed[0] = true;
                if (holder[0] != null) {
                    try {
                        holder[0].disconnect();
                    } catch (Exception ignored) {
                    }
                }
            }
        };
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
