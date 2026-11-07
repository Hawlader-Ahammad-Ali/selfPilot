package com.example.selfpilot.account;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional cloud backup.
 *
 * HARD RULE: this class is never on the path of a local feature. Focus Mode, the freezer,
 * exam mode and analytics read the Room database directly; if every call in here fails the
 * only visible effect is a "not synced" label on the account screen.
 */
public final class CloudSyncManager {

    private static final String TAG = "SELF_PILOT_CLOUD";

    private static final String PREFS = "SelfPilotCloud";
    private static final String KEY_LAST_AT = "last_sync_at";
    private static final String KEY_LAST_OK = "last_sync_ok";
    private static final String KEY_LAST_MSG = "last_sync_message";
    private static final String KEY_BACKUP_AT = "backup_created_at";

    /** Firestore documents are capped at 1 MiB; stay well below that. */
    private static final int MAX_PAYLOAD_CHARS = 900_000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Listener {
        void onResult(boolean success, String message);
    }

    private CloudSyncManager() {
    }

    // ------------------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------------------

    /** Uploads the current local data and refreshes the leaderboard row. */
    public static void syncNow(Context context, Listener listener) {
        if (context == null) {
            notify(listener, false, "Cloud sync unavailable");
            return;
        }
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            boolean ok;
            String message;
            try {
                ok = pushInternal(app);
                message = ok ? "Backup complete" : lastMessage(app);
            } catch (Exception e) {
                ok = false;
                message = "Sync failed - local data is safe";
                Log.w(TAG, "[SYNC] push failed", e);
                remember(app, false, message, null);
            }
            if (ok) remember(app, true, "Backup complete", "up");
            notify(listener, ok, message);
        });
    }

    /** Downloads the cloud backup and merges it into the local database. */
    public static void restore(Context context, Listener listener) {
        if (context == null) {
            notify(listener, false, "Cloud restore unavailable");
            return;
        }
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            boolean ok = false;
            String message;
            try {
                AccountSession session = AccountSession.load(app);
                if (!session.isSignedIn()) {
                    message = "Sign in to restore a backup";
                } else {
                    String token = AuthApi.validToken(app);
                    if (token == null) {
                        message = "Cloud unreachable - try again";
                    } else {
                        JSONObject fields = FirestoreApi.getFields("users/" + session.uid, token);
                        String payload = FirestoreApi.stringField(fields, "payload");
                        if (payload == null || payload.isEmpty()) {
                            message = "No cloud backup found for this account";
                        } else {
                            JSONObject root = new JSONObject(payload);
                            BackupCodec.importInto(app, root);
                            // After a restore the leaderboard should reflect the merged data.
                            LeaderboardRepository.publish(app, session, token);
                            ok = true;
                            message = "Backup restored to this device";
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "[SYNC] restore failed", e);
                message = "Restore failed - local data is safe";
            }
            remember(app, ok, message, ok ? "down" : null);
            notify(listener, ok, message);
        });
    }

    /** Blocking upload used by {@link CloudBackupWorker}. Never throws. */
    public static boolean syncBlocking(Context context) {
        try {
            if (context == null || !AccountSession.isSignedIn(context)) return false;
            return pushInternal(context.getApplicationContext());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Guest-to-account migration and multi-device merge.
     *
     * The cloud copy is merged into the local database first (additive, keyed by date /
     * package / session start), then the merged result is uploaded. That means a guest who
     * signs in keeps everything they had, and a second device picks up the history without
     * overwriting what is already on it.
     */
    public static void migrateThenSync(Context context, Listener listener) {
        if (context == null) {
            notify(listener, false, "Cloud sync unavailable");
            return;
        }
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            boolean ok;
            String message;
            try {
                AccountSession session = AccountSession.load(app);
                if (!session.isSignedIn()) {
                    notify(listener, false, "Sign in to link this device");
                    return;
                }
                String token = AuthApi.validToken(app);
                if (token != null) {
                    JSONObject fields = FirestoreApi.getFields("users/" + session.uid, token);
                    String payload = FirestoreApi.stringField(fields, "payload");
                    if (payload != null && !payload.isEmpty()) {
                        BackupCodec.importInto(app, new JSONObject(payload));
                    }
                }
                ok = pushInternal(app);
                message = ok
                        ? "Local data linked to your account"
                        : "Linked on this device - cloud retry later";
            } catch (Exception e) {
                Log.w(TAG, "[SYNC] migration failed", e);
                ok = false;
                message = "Cloud unavailable - local data is safe";
            }
            remember(app, ok, message, "up");
            notify(listener, ok, message);
        });
    }

    /**
     * Opportunistic background sync: runs at most once per {@code minIntervalMs}, silently.
     * Called when the app comes to the foreground.
     */
    public static void syncIfDue(Context context, long minIntervalMs) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        if (!AccountSession.isSignedIn(app)) return;
        if (!PrivacySettings.autoBackup(app)) return;

        long last = lastSyncAt(app);
        if (System.currentTimeMillis() - last < minIntervalMs) return;

        IO.execute(() -> {
            try {
                if (pushInternal(app)) {
                    remember(app, true, "Backup complete", "up");
                }
            } catch (Exception e) {
                Log.w(TAG, "[SYNC] background sync failed", e);
                remember(app, false, "Sync failed - local data is safe", null);
            }
        });
    }

    public static boolean hasCloudBackup(Context context) {
        try {
            AccountSession session = AccountSession.load(context);
            if (!session.isSignedIn()) return false;
            String token = AuthApi.validToken(context);
            if (token == null) return false;
            JSONObject fields = FirestoreApi.getFields("users/" + session.uid, token);
            String payload = FirestoreApi.stringField(fields, "payload");
            return payload != null && !payload.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------------------------

    public static String statusLine(Context context) {
        if (context == null) return "Cloud sync off";
        AccountSession session = AccountSession.load(context);
        if (!session.isSignedIn()) return "Guest mode - nothing is uploaded";

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long at = prefs.getLong(KEY_LAST_AT, 0L);
        boolean ok = prefs.getBoolean(KEY_LAST_OK, true);
        if (at == 0L) return "Signed in - not backed up yet";

        String when = formatTime(at);
        if (!ok) {
            String msg = prefs.getString(KEY_LAST_MSG, "Sync failed");
            return msg + " (last attempt " + when + ")";
        }
        String direction = "down".equals(prefs.getString("last_direction", "up"))
                ? "Restored " : "Backed up ";
        return direction + when;
    }

    public static long lastSyncAt(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_AT, 0L);
    }

    public static long backupCreatedAt(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_BACKUP_AT, 0L);
    }

    private static String formatTime(long millis) {
        try {
            return new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
                    .format(new Date(millis));
        } catch (Exception e) {
            return "just now";
        }
    }

    // ------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------

    private static boolean pushInternal(Context context) throws Exception {
        AccountSession session = AccountSession.load(context);
        if (!session.isSignedIn()) return false;

        String token = AuthApi.validToken(context);
        if (token == null) return false;

        JSONObject payload = BackupCodec.export(context);
        String serialized = payload.toString();
        if (serialized.length() > MAX_PAYLOAD_CHARS) {
            serialized = shrink(payload);
        }

        JSONObject fields = new JSONObject();
        fields.put("payload", FirestoreApi.str(serialized));
        fields.put("name", FirestoreApi.str(session.safeName()));
        fields.put("email", FirestoreApi.str(session.email == null ? "" : session.email));
        fields.put("version", FirestoreApi.num(BackupCodec.VERSION));
        fields.put("updatedAt", FirestoreApi.num(System.currentTimeMillis()));

        boolean written = FirestoreApi.write("users/" + session.uid, fields, token);
        if (!written) return false;

        // Never let a leaderboard problem mark the backup itself as failed.
        try {
            LeaderboardRepository.publish(context, session, token);
        } catch (Exception ignored) {
        }

        // Realtime Database: live session node + device registry for multi-device support.
        try {
            if (PrivacySettings.realtimeSync(context)) {
                SessionSyncManager.publish(context);
            }
        } catch (Exception ignored) {
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_BACKUP_AT, System.currentTimeMillis())
                .apply();
        return true;
    }

    /** Drops the oldest history first until the document fits Firestore's limit. */
    private static String shrink(JSONObject payload) {
        try {
            JSONArray sessions = payload.optJSONArray("focusSessions");
            if (sessions != null && sessions.length() > 200) {
                JSONArray trimmed = new JSONArray();
                for (int i = 0; i < 200; i++) trimmed.put(sessions.get(i));
                payload.put("focusSessions", trimmed);
            }
            JSONArray scores = payload.optJSONArray("scores");
            if (scores != null && scores.length() > 400) {
                JSONArray trimmed = new JSONArray();
                for (int i = 0; i < 400; i++) trimmed.put(scores.get(i));
                payload.put("scores", trimmed);
            }
            String result = payload.toString();
            if (result.length() > MAX_PAYLOAD_CHARS) {
                payload.put("focusSessions", new JSONArray());
                result = payload.toString();
            }
            return result;
        } catch (Exception e) {
            return payload.toString();
        }
    }

    private static void remember(Context context, boolean ok, String message, String direction) {
        SharedPreferences.Editor editor = context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_AT, System.currentTimeMillis())
                .putBoolean(KEY_LAST_OK, ok)
                .putString(KEY_LAST_MSG, message);
        if (direction != null) editor.putString("last_direction", direction);
        editor.apply();
    }

    private static String lastMessage(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_MSG, "Sync failed - local data is safe");
    }

    private static void notify(Listener listener, boolean success, String message) {
        if (listener == null) return;
        MAIN.post(() -> listener.onResult(success, message));
    }
}
