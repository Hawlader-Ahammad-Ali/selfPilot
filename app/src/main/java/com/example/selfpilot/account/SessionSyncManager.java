package com.example.selfpilot.account;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.ExamProfileEntity;
import com.example.selfpilot.data.FocusSessionEntity;
import com.example.selfpilot.service.FocusTimerService;

import org.json.JSONObject;

import java.util.List;
import java.util.UUID;

/**
 * Realtime session channel (Firebase Realtime Database).
 *
 * The device that is running a session publishes a small status node. Other devices of the
 * same account subscribe and simply show it. This is deliberately one-way: a remote device
 * can never start, stop or unlock a focus/exam session on this phone, so a sync problem or a
 * bad payload can never weaken enforcement.
 */
public final class SessionSyncManager {

    private static final String TAG = "SELF_PILOT_SESSION_SYNC";
    private static final String PREFS = "SelfPilotDevice";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_NAME = "device_name";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static RtDatabase.Stream stream;
    private static String streamUid;

    private SessionSyncManager() {
    }

    // ------------------------------------------------------------------------------------
    // Device identity
    // ------------------------------------------------------------------------------------

    public static String deviceId(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    public static String deviceName(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String name = prefs.getString(KEY_DEVICE_NAME, null);
        if (name == null) {
            String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
            String model = Build.MODEL == null ? "Android" : Build.MODEL;
            name = (manufacturer + " " + model).trim();
            prefs.edit().putString(KEY_DEVICE_NAME, name).apply();
        }
        return name;
    }

    // ------------------------------------------------------------------------------------
    // Publishing
    // ------------------------------------------------------------------------------------

    /**
     * Writes the current session snapshot for this account. Called when a session starts or
     * ends, and on app start. Safe to call for guests (no-op).
     */
    public static void publish(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        if (!AccountSession.isSignedIn(app)) return;
        if (!PrivacySettings.realtimeSync(app)) return;

        final String uid = AccountSession.load(app).uid;

        new Thread(() -> {
            try {
                String token = AuthApi.validToken(app);
                if (token == null) return;

                JSONObject node = new JSONObject();
                boolean focusActive = FocusTimerService.isRunning();
                node.put("focusActive", focusActive);
                node.put("focusEndAt", focusActive
                        ? System.currentTimeMillis() + FocusTimerService.getTimeLeftMs() : 0L);

                AppDatabase db = AppDatabase.getDatabase(app);
                ExamProfileEntity exam = activeExam(db);
                node.put("examActive", exam != null);
                node.put("examName", exam == null ? "" : String.valueOf(exam.examName));
                node.put("examEndAt", exam == null ? 0L : exam.endDate);

                node.put("device", deviceName(app));
                node.put("deviceId", deviceId(app));
                node.put("updatedAt", System.currentTimeMillis());

                RtDatabase.put("sessions/" + uid, node.toString(), token);

                // Device registry powers the "multi-device" card.
                JSONObject device = new JSONObject();
                device.put("name", deviceName(app));
                device.put("lastSeen", System.currentTimeMillis());
                RtDatabase.put("devices/" + uid + "/" + deviceId(app), device.toString(), token);
            } catch (Exception e) {
                Log.w(TAG, "[SESSION] publish failed", e);
            }
        }, "selfpilot-session-publish").start();
    }

    private static ExamProfileEntity activeExam(AppDatabase db) {
        try {
            List<ExamProfileEntity> exams = db.examProfileDao().getActiveExams();
            if (exams == null || exams.isEmpty()) return null;
            long now = System.currentTimeMillis();
            for (ExamProfileEntity exam : exams) {
                if (exam.isActive && exam.endDate > now && exam.startDate <= now) return exam;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------
    // Listening
    // ------------------------------------------------------------------------------------

    public static void startListening(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        if (!AccountSession.isSignedIn(app)) return;
        if (!PrivacySettings.realtimeSync(app)) return;

        final String uid = AccountSession.load(app).uid;
        if (stream != null && uid.equals(streamUid)) return;
        stopListening();

        new Thread(() -> {
            try {
                String token = AuthApi.validToken(app);
                if (token == null) return;
                streamUid = uid;
                stream = RtDatabase.stream("sessions/" + uid, token,
                        new RtDatabase.StreamListener() {
                            @Override
                            public void onValue(String data) {
                                applyRemote(app, uid, data);
                            }

                            @Override
                            public void onClosed(String reason) {
                                Log.i(TAG, "[SESSION] stream closed: " + reason);
                            }
                        });
            } catch (Exception e) {
                Log.w(TAG, "[SESSION] listen failed", e);
            }
        }, "selfpilot-session-listen").start();
    }

    public static void stopListening() {
        try {
            if (stream != null) stream.close();
        } catch (Exception ignored) {
        }
        stream = null;
        streamUid = null;
    }

    private static void applyRemote(Context context, String uid, String data) {
        try {
            if (data == null || "null".equals(data.trim())) return;
            JSONObject node = new JSONObject(data);

            // Ignore our own writes: this device already knows its state.
            if (deviceId(context).equals(node.optString("deviceId", ""))) return;

            boolean focusActive = node.optBoolean("focusActive", false)
                    && node.optLong("focusEndAt", 0L) > System.currentTimeMillis();

            SharedPreferences prefs = context.getSharedPreferences("SelfPilotRemote", Context.MODE_PRIVATE);
            prefs.edit()
                    .putString("uid", uid)
                    .putBoolean("focus_active", focusActive)
                    .putBoolean("exam_active", node.optBoolean("examActive", false))
                    .putString("exam_name", node.optString("examName", ""))
                    .putString("device", node.optString("device", "another device"))
                    .putLong("updated_at", node.optLong("updatedAt", System.currentTimeMillis()))
                    .apply();

            MAIN.post(() -> {
                for (Runnable listener : RemoteListenerRegistry.listeners()) {
                    try {
                        listener.run();
                    } catch (Exception ignored) {
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "[SESSION] bad remote payload", e);
        }
    }

    // ------------------------------------------------------------------------------------
    // Remote snapshot for the UI
    // ------------------------------------------------------------------------------------

    public interface TextCallback {
        void onResult(String value);
    }

    /** Lists the devices that have synced this account, newest first. */
    public static void listDevices(Context context, TextCallback callback) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        if (!AccountSession.isSignedIn(app)) {
            if (callback != null) callback.onResult(null);
            return;
        }
        new Thread(() -> {
            String result = null;
            try {
                String token = AuthApi.validToken(app);
                String uid = AccountSession.load(app).uid;
                if (token != null && uid != null) {
                    String json = RtDatabase.get("devices/" + uid, token);
                    result = summarizeDevices(json);
                }
            } catch (Exception ignored) {
            }
            if (callback != null) callback.onResult(result);
        }, "selfpilot-devices").start();
    }

    private static String summarizeDevices(String json) {
        if (json == null || "null".equals(json.trim())) return null;
        try {
            JSONObject root = new JSONObject(json);
            StringBuilder builder = new StringBuilder();
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                JSONObject device = root.optJSONObject(keys.next());
                if (device == null) continue;
                String name = device.optString("name", "Device");
                if (builder.length() > 0) builder.append(" • ");
                builder.append(name);
            }
            return builder.length() == 0 ? null : builder.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Removes the live node, e.g. on sign-out or account deletion. */
    public static void clearRemoteNode(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                AccountSession session = AccountSession.load(app);
                if (!session.isSignedIn()) return;
                String token = AuthApi.validToken(app);
                if (token == null) return;
                RtDatabase.delete("sessions/" + session.uid, token);
            } catch (Exception ignored) {
            }
        }, "selfpilot-session-clear").start();
    }

    public static String remoteSummary(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("SelfPilotRemote", Context.MODE_PRIVATE);
        String device = prefs.getString("device", null);
        if (device == null) return null;

        boolean focus = prefs.getBoolean("focus_active", false);
        boolean exam = prefs.getBoolean("exam_active", false);
        if (!focus && !exam) return null;

        if (focus) return "Focus session running on " + device;
        String name = prefs.getString("exam_name", "");
        return "Exam mode " + (name == null || name.isEmpty() ? "" : name + " ") + "running on " + device;
    }

    /** Simple in-process registry so an open screen can refresh itself. */
    public static final class RemoteListenerRegistry {
        private static final java.util.List<Runnable> LISTENERS = new java.util.ArrayList<>();

        public static void add(Runnable runnable) {
            if (runnable != null && !LISTENERS.contains(runnable)) LISTENERS.add(runnable);
        }

        public static void remove(Runnable runnable) {
            LISTENERS.remove(runnable);
        }

        static java.util.List<Runnable> listeners() {
            return new java.util.ArrayList<>(LISTENERS);
        }
    }
}
