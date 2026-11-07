package com.example.selfpilot.account;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin Firestore REST wrapper. Documents are addressed by collection/document id, and values
 * are built with the explicit Firestore typed-value form the REST API expects.
 */
final class FirestoreApi {

    private FirestoreApi() {
    }

    static String docUrl(String path) {
        return CloudConfig.FIRESTORE_BASE + "/" + path;
    }

    // ------------------------------------------------------------------------------------
    // Typed values
    // ------------------------------------------------------------------------------------

    static JSONObject str(String value) throws Exception {
        JSONObject value0 = new JSONObject();
        value0.put("stringValue", value == null ? "" : value);
        return value0;
    }

    static JSONObject num(long value) throws Exception {
        JSONObject value0 = new JSONObject();
        value0.put("integerValue", String.valueOf(value));
        return value0;
    }

    static JSONObject bool(boolean value) throws Exception {
        JSONObject value0 = new JSONObject();
        value0.put("booleanValue", value);
        return value0;
    }

    // ------------------------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------------------------

    /** Returns the document fields, or null when the document does not exist. */
    static JSONObject getFields(String path, String token) {
        Http.Response response = Http.get(docUrl(path), token);
        if (response.code == 404) return null;
        if (!response.isOk()) return null;
        try {
            JSONObject root = new JSONObject(response.body);
            return root.optJSONObject("fields");
        } catch (Exception e) {
            return null;
        }
    }

    static String stringField(JSONObject fields, String key) {
        if (fields == null) return null;
        JSONObject value = fields.optJSONObject(key);
        if (value == null) return null;
        return value.optString("stringValue", null);
    }

    static long longField(JSONObject fields, String key) {
        if (fields == null) return 0L;
        JSONObject value = fields.optJSONObject(key);
        if (value == null) return 0L;
        try {
            return Long.parseLong(value.optString("integerValue", "0"));
        } catch (Exception e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------------------------

    /** Merge-writes the given fields. Missing fields keep their previous server value. */
    static boolean write(String path, JSONObject fields, String token) {
        try {
            JSONObject body = new JSONObject();
            body.put("fields", fields);
            Http.Response response = Http.patch(docUrl(path), body.toString(), token);
            return response.isOk();
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------
    // Query (leaderboard)
    // ------------------------------------------------------------------------------------

    static List<JSONObject> topBy(String collection, String field, int limit, String token) {
        List<JSONObject> documents = new ArrayList<>();
        try {
            JSONObject order = new JSONObject();
            order.put("field", new JSONObject().put("fieldPath", field));
            order.put("direction", "DESCENDING");

            JSONObject query = new JSONObject();
            query.put("from", new JSONArray().put(new JSONObject().put("collectionId", collection)));
            query.put("orderBy", new JSONArray().put(order));
            query.put("limit", limit);

            JSONObject body = new JSONObject();
            body.put("structuredQuery", query);

            Http.Response response = Http.postBearer(
                    CloudConfig.FIRESTORE_BASE + ":runQuery", body.toString(), token);
            if (!response.isOk()) return documents;

            JSONArray rows = new JSONArray(response.body);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                JSONObject document = row.optJSONObject("document");
                if (document == null) continue;
                JSONObject fields = document.optJSONObject("fields");
                if (fields != null) documents.add(fields);
            }
        } catch (Exception ignored) {
        }
        return documents;
    }
}
