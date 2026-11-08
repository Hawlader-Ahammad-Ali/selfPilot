package com.example.selfpilot.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.selfpilot.MainActivity;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.FrozenAppEntity;
import com.example.selfpilot.receiver.GuardianRestartReceiver;
import com.example.selfpilot.util.AudioEnforcer;
import com.example.selfpilot.util.ExamSessionManager;
import com.example.selfpilot.util.FocusSessionManager;
import com.example.selfpilot.util.FrozenAppsManager;
import com.example.selfpilot.util.RestrictionChecker;
import com.example.selfpilot.util.ShizukuUtil;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Always-on foreground service that keeps the freezer protecting the device.
 *
 * Responsibilities:
 *  - Keeps SelfPilot alive in the background even when the task is swiped away from recents
 *    (android:stopWithTask="false" + START_STICKY + alarm based resurrection).
 *  - Re-seeds the frozen app cache after a process restart so no launch window is unprotected.
 *  - Detects if the Accessibility service got switched off by the system or the user and
 *    warns immediately instead of silently failing to block anything.
 *  - Re-applies Shizuku based deep freezes after reboot.
 *  - Guarantees SelfPilot can never be in the frozen table itself.
 */
public class GuardianService extends Service {

    private static final String TAG = "SELF_PILOT_FREEZER";

    public static final String ACTION_START = "com.example.selfpilot.action.GUARDIAN_START";
    public static final String ACTION_STOP = "com.example.selfpilot.action.GUARDIAN_STOP";

    private static final String CHANNEL_ID = "SelfPilotGuardianChannel";
    private static final String ALERT_CHANNEL_ID = "SelfPilotGuardianAlertsChannel";
    private static final int NOTIFICATION_ID = 2001;
    private static final int ALERT_NOTIFICATION_ID = 2002;

    private static final long TICK_INTERVAL_MS = 30_000L;
    /** Music must stop within a few seconds of a limit expiring. */
    private static final long MEDIA_GUARD_INTERVAL_MS = 15_000L;
    /** Deep maintenance (DB purge, Shizuku re-freeze) every Nth tick. */
    private static final int MAINTENANCE_EVERY_N_TICKS = 20; // ~10 minutes
    private static final long ALERT_REPEAT_MS = 10 * 60 * 1000L;
    private static final long RESTART_ALARM_DELAY_MS = 3_000L;
    private static final int RESTART_REQUEST_CODE = 4242;

    /**
     * Repeating heartbeat. A one-shot restart alarm only exists after onDestroy()/onTaskRemoved();
     * if the process is killed outright (OEM task cleaners, low memory) no callback ever runs,
     * so the freezer would stay dead until the user opened the app. This alarm has no such gap.
     */
    private static final int HEARTBEAT_REQUEST_CODE = 4343;
    private static final long HEARTBEAT_INTERVAL_MS = 15 * 60 * 1000L;
    private static final long HEARTBEAT_FIRST_DELAY_MS = 10 * 60 * 1000L;

    /** Visible to the UI so the user can see whether background protection is alive. */
    public static volatile boolean RUNNING = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private int tickCounter = 0;
    private long lastAccessibilityAlertAt = 0L;
    private volatile boolean ticking = false;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!ticking) return;
            try {
                runTick();
            } catch (Throwable t) {
                Log.w(TAG, "[GUARDIAN] tick failed", t);
            }
            if (ticking) {
                handler.postDelayed(this, TICK_INTERVAL_MS);
            }
        }
    };

    // ------------------------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------------------------

    public static void start(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context.getApplicationContext(), GuardianService.class);
            intent.setAction(ACTION_START);
            ContextCompat.startForegroundService(context.getApplicationContext(), intent);
        } catch (Throwable t) {
            Log.e(TAG, "[GUARDIAN] Unable to start GuardianService", t);
        }
    }

    public static boolean isRunning() {
        return RUNNING;
    }

    public static void stop(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(context.getApplicationContext(), GuardianService.class);
            intent.setAction(ACTION_STOP);
            context.getApplicationContext().startService(intent);
        } catch (Throwable t) {
            Log.w(TAG, "[GUARDIAN] Unable to stop GuardianService", t);
        }
    }

    public static boolean isAccessibilityServiceEnabled(Context context) {
        try {
            ComponentName component = new ComponentName(context, AppLockAccessibilityService.class);
            String expected = component.flattenToString();
            String expectedShort = component.flattenToShortString();

            String enabled = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;

            for (String entry : enabled.split(":")) {
                if (entry.equalsIgnoreCase(expected)
                        || entry.equalsIgnoreCase(expectedShort)
                        || entry.replace('/', '|').equalsIgnoreCase(expected.replace('/', '|'))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------
    // Service lifecycle
    // ------------------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        RUNNING = true;
        createChannels();
        Log.i(TAG, "[GUARDIAN] onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            ticking = false;
            RUNNING = false;
            handler.removeCallbacksAndMessages(null);
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        RUNNING = true;

        // Must be called promptly or the system throws for startForegroundService().
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Protection active", false));
        } catch (Throwable t) {
            Log.e(TAG, "[GUARDIAN] startForeground failed", t);
        }

        if (!ticking) {
            ticking = true;
            handler.post(tickRunnable);
            handler.post(mediaGuardRunnable);
        }

        // Do the cheap critical work immediately on every start.
        FrozenAppsManager.init(getApplicationContext());
        scheduleHeartbeat();

        return START_STICKY;
    }

    /**
     * Swiping SelfPilot out of recents must not disable the freezer: self-pilot has to keep
     * running in the background. The foreground service survives, and this alarm guarantees
     * a restart even if an aggressive OEM task killer removes the process.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.w(TAG, "[GUARDIAN] Task removed from recents -> keeping protection alive");
        scheduleRestart();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "[GUARDIAN] onDestroy -> scheduling restart");
        ticking = false;
        RUNNING = false;
        handler.removeCallbacksAndMessages(null);
        scheduleRestart();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------------------------
    // Periodic maintenance
    // ------------------------------------------------------------------------------------

    /**
     * Playback guard.
     *
     * Blocking the screen does not stop sound: a music app whose limit just ran out keeps
     * playing from the background. This runs four times a minute, and any restricted package
     * that still owns a playing media session is paused immediately.
     */
    private final Runnable mediaGuardRunnable = new Runnable() {
        @Override
        public void run() {
            if (!ticking) return;
            try {
                executor.execute(GuardianService.this::runMediaGuard);
            } catch (Throwable t) {
                Log.w(TAG, "[GUARDIAN] media guard could not be queued", t);
            }
            if (ticking) {
                handler.postDelayed(this, MEDIA_GUARD_INTERVAL_MS);
            }
        }
    };

    private void runMediaGuard() {
        try {
            List<String> playing = AudioEnforcer.playingPackages(this);
            if (playing.isEmpty()) return;

            for (String packageName : playing) {
                try {
                    RestrictionChecker.BlockDecision decision =
                            RestrictionChecker.evaluate(this, packageName);
                    if (!decision.isBlocked()) continue;

                    AudioEnforcer.stopPlayback(this, packageName);
                    Log.i(TAG, "[GUARDIAN] Stopped background audio of blocked app "
                            + packageName + " (" + decision.reason + ")");
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "[GUARDIAN] media guard failed", t);
        }
    }

    private void runTick() {
        tickCounter++;

        boolean accessibilityEnabled = isAccessibilityServiceEnabled(this);
        if (!accessibilityEnabled) {
            warnAccessibilityDisabled();
        }

        // The cache may have been dropped with the process that hosted it.
        FrozenAppsManager.ensureInitialized(this);

        // Exam Mode must not depend solely on its alarms: catch up on missed start/end events.
        executor.execute(() -> ExamSessionManager.syncStateAndRescore(this));

        // Same for Focus Mode: a session whose end time has passed must stop blocking apps
        // even if its foreground service died.
        executor.execute(() -> FocusSessionManager.syncState(this));

        final int frozenCount = FrozenAppsManager.getFrozenPackages().size();
        updateNotification(frozenCount, accessibilityEnabled);

        if (tickCounter % MAINTENANCE_EVERY_N_TICKS == 0) {
            executor.execute(this::runDeepMaintenance);
        }
    }

    private void runDeepMaintenance() {
        try {
            FrozenAppsManager.purgeSelf(this);

            AppDatabase db = AppDatabase.getDatabase(getApplicationContext());
            List<FrozenAppEntity> frozenApps = db.frozenAppDao().getFrozenApps();
            if (frozenApps == null || frozenApps.isEmpty()) return;

            if (!ShizukuUtil.hasShizukuPermission()) return;

            for (FrozenAppEntity frozen : frozenApps) {
                if (!frozen.isFrozen) continue;
                // Re-apply the deep freeze in case the app was re-enabled outside SelfPilot.
                ShizukuUtil.freezeApp(frozen.packageName);
            }
            Log.i(TAG, "[GUARDIAN] Deep freeze re-applied to " + frozenApps.size() + " app(s)");
        } catch (Throwable t) {
            Log.w(TAG, "[GUARDIAN] deep maintenance failed", t);
        }
    }

    private void warnAccessibilityDisabled() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastAccessibilityAlertAt < ALERT_REPEAT_MS) return;
        lastAccessibilityAlertAt = now;

        Log.w(TAG, "[GUARDIAN] Accessibility service is DISABLED - freezer cannot enforce");

        try {
            Intent open = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(
                    this, 2002, open, pendingIntentFlags());

            Notification notification = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                    .setContentTitle("Freezer is not protecting you")
                    .setContentText("Enable the SelfPilot accessibility service to block frozen apps.")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build();

            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(ALERT_NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[GUARDIAN] failed to post accessibility alert", t);
        }
    }

    // ------------------------------------------------------------------------------------
    // Restart resilience
    // ------------------------------------------------------------------------------------

    private void scheduleRestart() {
        try {
            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (alarmManager == null) return;

            Intent intent = new Intent(this, GuardianRestartReceiver.class);
            intent.setAction(ACTION_START);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this, RESTART_REQUEST_CODE, intent, pendingIntentFlags());

            // setAndAllowWhileIdle works without SCHEDULE_EXACT_ALARM on every API level.
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + RESTART_ALARM_DELAY_MS,
                    pendingIntent);
            Log.i(TAG, "[GUARDIAN] Restart alarm scheduled");
        } catch (Throwable t) {
            Log.w(TAG, "[GUARDIAN] failed to schedule restart", t);
        }
    }

    /**
     * Repeating, inexact alarm: survives silent process death with no lifecycle callback.
     * Rescheduled on every service start, and again after a reboot by BootReceiver.
     */
    private void scheduleHeartbeat() {
        try {
            AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (alarmManager == null) return;

            Intent intent = new Intent(this, GuardianRestartReceiver.class);
            intent.setAction(ACTION_START);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this, HEARTBEAT_REQUEST_CODE, intent, pendingIntentFlags());

            alarmManager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + HEARTBEAT_FIRST_DELAY_MS,
                    HEARTBEAT_INTERVAL_MS,
                    pendingIntent);
            Log.i(TAG, "[GUARDIAN] Heartbeat scheduled every " + (HEARTBEAT_INTERVAL_MS / 60000) + " min");
        } catch (Throwable t) {
            Log.w(TAG, "[GUARDIAN] failed to schedule heartbeat", t);
        }
    }

    private int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    // ------------------------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------------------------

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;

        NotificationChannel guardian = new NotificationChannel(
                CHANNEL_ID, "SelfPilot Protection", NotificationManager.IMPORTANCE_MIN);
        guardian.setDescription("Keeps the app freezer running in the background");
        guardian.setShowBadge(false);
        manager.createNotificationChannel(guardian);

        NotificationChannel alerts = new NotificationChannel(
                ALERT_CHANNEL_ID, "SelfPilot Alerts", NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription("Warns when protection is switched off");
        manager.createNotificationChannel(alerts);
    }

    private Notification buildNotification(String contentText, boolean alert) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 2001, open, pendingIntentFlags());

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SelfPilot is protecting you")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(alert ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_MIN)
                .setContentIntent(contentIntent)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void updateNotification(int frozenCount, boolean accessibilityEnabled) {
        try {
            String text;
            if (!accessibilityEnabled) {
                text = "Accessibility service is OFF - tap to fix";
            } else if (frozenCount == 0) {
                text = "No frozen apps. Background guard is running.";
            } else {
                text = frozenCount + " app(s) frozen - launches are blocked";
            }
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification(text, !accessibilityEnabled));
            }
        } catch (Throwable t) {
            Log.w(TAG, "[GUARDIAN] failed to update notification", t);
        }
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignored) {
        }
    }
}


