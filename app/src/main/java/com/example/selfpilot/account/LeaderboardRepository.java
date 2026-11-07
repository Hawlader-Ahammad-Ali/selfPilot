package com.example.selfpilot.account;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Global leaderboard, ranked by total study points.
 *
 * Reading requires a signed-in account; guests keep using every local feature and simply see
 * a "sign in to compete" message instead. A failed request never blocks the screen.
 */
public final class LeaderboardRepository {

    private static final String TAG = "SELF_PILOT_LEADERBOARD";
    private static final String COLLECTION = "leaderboard";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static class Row {
        public String uid;
        public String name;
        public long points;
        public int discipline;
        public int focusMinutes;
        public int rank;
        public boolean isMe;
    }

    public interface Callback {
        void onResult(boolean success, List<Row> rows, String message);
    }

    private LeaderboardRepository() {
    }

    /** Blocking upload of this account's own row. Called from {@link CloudSyncManager}. */
    static boolean publish(Context context, AccountSession session, String token) {
        try {
            // Privacy control: "off" removes the public row entirely.
            String displayName = PrivacySettings.leaderboardName(context, session);
            if (displayName == null) {
                Http.delete(FirestoreApi.docUrl(COLLECTION + "/" + session.uid), token);
                return true;
            }

            long points = 0L;
            int focusMinutes = 0;
            int discipline = 100;

            try {
                com.example.selfpilot.data.AppDatabase db =
                        com.example.selfpilot.data.AppDatabase.getDatabase(context);
                points = db.honorScoreDao().getTotalStudyPoints()
                        + db.focusSessionDao().getTotalXp();
                focusMinutes = db.focusSessionDao().getTotalFocusMinutes();
                com.example.selfpilot.data.HonorScoreEntity latest =
                        db.honorScoreDao().getLatestScore();
                if (latest != null) discipline = latest.getDisciplineScore();
            } catch (Exception e) {
                Log.w(TAG, "[LEADERBOARD] could not read local totals", e);
            }

            JSONObject fields = new JSONObject();
            fields.put("name", FirestoreApi.str(displayName));
            fields.put("points", FirestoreApi.num(points));
            fields.put("focusMinutes", FirestoreApi.num(focusMinutes));
            fields.put("discipline", FirestoreApi.num(discipline));
            fields.put("updatedAt", FirestoreApi.num(System.currentTimeMillis()));

            return FirestoreApi.write(COLLECTION + "/" + session.uid, fields, token);
        } catch (Exception e) {
            Log.w(TAG, "[LEADERBOARD] publish failed", e);
            return false;
        }
    }

    public static void fetch(Context context, int limit, Callback callback) {
        if (context == null) {
            if (callback != null) callback.onResult(false, new ArrayList<>(), "Unavailable");
            return;
        }
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            List<Row> rows = new ArrayList<>();
            boolean success = false;
            String message = "";

            try {
                AccountSession session = AccountSession.load(app);
                if (!session.isSignedIn()) {
                    message = "Sign in to see the leaderboard";
                } else {
                    String token = AuthApi.validToken(app);
                    if (token == null) {
                        message = "Sign in again to load the leaderboard";
                    } else {
                        List<JSONObject> documents =
                                FirestoreApi.topBy(COLLECTION, "points", limit, token);
                        int rank = 0;
                        for (JSONObject fields : documents) {
                            rank++;
                            Row row = new Row();
                            row.rank = rank;
                            row.name = FirestoreApi.stringField(fields, "name");
                            row.points = FirestoreApi.longField(fields, "points");
                            row.focusMinutes = (int) FirestoreApi.longField(fields, "focusMinutes");
                            row.discipline = (int) FirestoreApi.longField(fields, "discipline");
                            if (row.name == null || row.name.isEmpty()) row.name = "Anonymous";
                            rows.add(row);
                        }

                        // Mark the current user, even when their row is below the page limit.
                        String myName = PrivacySettings.leaderboardName(app, session);
                        boolean found = false;
                        for (Row row : rows) {
                            if (myName.equals(row.name)) {
                                row.isMe = true;
                                found = true;
                            }
                        }
                        success = true;
                        if (rows.isEmpty()) message = "No entries yet - be the first";
                        else if (!found) message = "You are outside the top " + limit;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "[LEADERBOARD] fetch failed", e);
                message = "Cloud unreachable - showing nothing";
            }

            final boolean finalSuccess = success;
            final String finalMessage = message;
            MAIN.post(() -> {
                if (callback != null) callback.onResult(finalSuccess, rows, finalMessage);
            });
        });
    }
}
