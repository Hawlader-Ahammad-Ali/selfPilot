package com.example.selfpilot.account;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal blocking HTTP helper. Only ever called from a worker thread; a short timeout means a
 * dead connection can never hold a thread for more than ~20 seconds.
 */
final class Http {

    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }

        boolean isOk() {
            return code >= 200 && code < 300;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 20000;

    private Http() {
    }

    static Response get(String url, String bearer) {
        return send("GET", url, null, bearer);
    }

    static Response post(String url, String json) {
        return send("POST", url, json, null);
    }

    static Response postBearer(String url, String json, String bearer) {
        return send("POST", url, json, bearer);
    }

    static Response patch(String url, String json, String bearer) {
        return send("PATCH", url, json, bearer);
    }

    static Response delete(String url, String bearer) {
        return send("DELETE", url, null, bearer);
    }

    /** PUT / DELETE against a service whose credentials travel in the URL. */
    static Response sendRaw(String method, String url, String body, String contentType) {
        return send(method, url, body, null);
    }

    private static Response send(String method, String url, String body, String bearer) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            if (bearer != null) {
                conn.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                }
            }

            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new Response(code, readAll(stream));
        } catch (Exception e) {
            return new Response(-1, String.valueOf(e.getMessage()));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream stream) {
        if (stream == null) return "";
        try (InputStream in = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
