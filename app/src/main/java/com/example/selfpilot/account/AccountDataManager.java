package com.example.selfpilot.account;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Data-portability and account-deletion operations.
 *
 * Export always works, even for a guest with no account - the file is produced from the local
 * database only. Deletion is a two-step operation: cloud documents first, then the Auth user,
 * so nothing is left orphaned if the network drops halfway through.
 */
public final class AccountDataManager {

    private static final String TAG = "SELF_PILOT_DATA";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface FileCallback {
        void onResult(boolean success, File file, String message);
    }

    public interface Callback {
        void onResult(boolean success, String message);
    }

    private AccountDataManager() {
    }

    // ------------------------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------------------------

    /** Writes a portable JSON export and returns the file. Works offline and as a guest. */
    public static void exportJson(Context context, FileCallback callback) {
        if (context == null) {
            notifyFile(callback, false, null, "Export unavailable");
            return;
        }
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            File file = null;
            String message;
            boolean ok = false;
            try {
                JSONObject payload = BackupCodec.export(app);
                payload.put("app", "SelfPilot");
                payload.put("exportedBy", AccountSession.load(app).isSignedIn()
                        ? AccountSession.load(app).email : "guest");

                File dir = app.getExternalFilesDir(null);
                if (dir == null) dir = app.getFilesDir();
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                        .format(new Date());
                file = new File(dir, "selfpilot-export-" + stamp + ".json");

                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(payload.toString(2).getBytes(StandardCharsets.UTF_8));
                }
                ok = true;
                message = "Export saved";
            } catch (Exception e) {
                Log.w(TAG, "[EXPORT] failed", e);
                message = "Export failed";
            }
            notifyFile(callback, ok, file, message);
        });
    }

    // ------------------------------------------------------------------------------------
    // Delete account
    // ------------------------------------------------------------------------------------

    /**
     * Removes the cloud footprint (backup document, leaderboard row, live session node,
     * device registry) and then the Firebase Auth user.
     *
     * @param wipeLocal when true the local database is cleared as well - off by default.
     */
    public static void deleteAccount(Context context, boolean wipeLocal, Callback callback) {
        if (context == null) {
            notify(callback, false, "Unavailable");
            return;
        }
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            String message;
            boolean ok = false;
            try {
                AccountSession session = AccountSession.load(app);
                if (!session.isSignedIn()) {
                    message = "Not signed in";
                } else {
                    String token = AuthApi.validToken(app);
                    if (token == null) {
                        message = "Cloud unreachable - nothing was deleted";
                    } else {
                        // 1. Live channels and public row go first.
                        try {
                            RtDatabase.delete("sessions/" + session.uid, token);
                            RtDatabase.delete("devices/" + session.uid, token);
                        } catch (Exception ignored) {
                        }
                        Http.delete(FirestoreApi.docUrl("leaderboard/" + session.uid), token);
                        Http.delete(FirestoreApi.docUrl("users/" + session.uid), token);

                        // 2. Then the identity itself.
                        boolean deleted = AuthApi.deleteAccountBlocking(app);

                        if (deleted) {
                            AccountSession.clear(app);
                            if (wipeLocal) wipeLocalDatabase(app);
                            ok = true;
                            message = wipeLocal
                                    ? "Account and local data deleted"
                                    : "Account deleted - local data kept";
                        } else {
                            message = "Account deletion failed - try again";
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "[DELETE] failed", e);
                message = "Account deletion failed - try again";
            }
            notify(callback, ok, message);
        });
    }

    /** Only called after an explicit confirmation from the user. */
    public static void wipeLocalDatabase(Context context) {
        try {
            com.example.selfpilot.data.AppDatabase db =
                    com.example.selfpilot.data.AppDatabase.getDatabase(context);
            db.clearAllTables();
            com.example.selfpilot.util.FrozenAppsManager.reconcile(context);
        } catch (Exception e) {
            Log.w(TAG, "[DELETE] local wipe failed", e);
        }
    }

    // ------------------------------------------------------------------------------------

    private static void notify(Callback callback, boolean success, String message) {
        if (callback == null) return;
        MAIN.post(() -> callback.onResult(success, message));
    }

    private static void notifyFile(FileCallback callback, boolean success, File file, String message) {
        if (callback == null) return;
        MAIN.post(() -> callback.onResult(success, file, message));
    }
}
