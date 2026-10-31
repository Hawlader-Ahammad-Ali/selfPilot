package com.example.selfpilot.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.FocusSessionEntity;
import com.example.selfpilot.util.FocusSessionManager;

public class FocusTimerService extends Service {

    private static final String TAG = "SELF_PILOT_FREEZER";

    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_QUERY_STATUS = "ACTION_QUERY_STATUS";
    public static final String ACTION_RECOVER = "ACTION_RECOVER";

    public static final String EXTRA_DURATION = "EXTRA_DURATION";

    public static final String ACTION_TIMER_TICK = "com.example.selfpilot.TIMER_TICK";
    public static final String ACTION_TIMER_FINISHED = "com.example.selfpilot.TIMER_FINISHED";

    public static final String EXTRA_TIME_LEFT = "EXTRA_TIME_LEFT";
    public static final String EXTRA_XP_EARNED = "EXTRA_XP_EARNED";
    public static final String EXTRA_IS_RUNNING = "EXTRA_IS_RUNNING";

    private static final String CHANNEL_ID = "FocusTimerChannel";
    private static final int NOTIFICATION_ID = 1001;

    /** Snapshot for the UI, so the fragment never has to spawn the service just to ask. */
    private static volatile boolean running = false;
    private static volatile long lastKnownTimeLeft = 0L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private CountDownTimer countDownTimer;
    private long timeLeftInMillis;
    private int activeSessionId = -1;
    private boolean isRunning = false;

    public static boolean isRunning() {
        return running;
    }

    public static long getTimeLeftMs() {
        return lastKnownTimeLeft;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Enter the foreground state immediately and unconditionally. After a process death
        // the system restarts a START_STICKY service with a null intent, and skipping
        // startForeground() there ends in a RemoteServiceException crash loop.
        startForegroundSafely("Focus session active");
        running = true;

        String action = intent != null ? intent.getAction() : null;

        if (ACTION_START.equals(action)) {
            long durationMins = intent.getIntExtra(EXTRA_DURATION, 25);
            startNewSession(durationMins);
        } else {
            // ACTION_RECOVER, ACTION_QUERY_STATUS, or null after a system restart.
            recoverSession();
        }

        return START_STICKY;
    }

    // ------------------------------------------------------------------------------------
    // Session handling
    // ------------------------------------------------------------------------------------

    private void startNewSession(final long durationMins) {
        new Thread(() -> {
            try {
                AppDatabase db = AppDatabase.getDatabase(getApplicationContext());
                FocusSessionEntity existing = db.focusSessionDao().getActiveSession();

                if (existing != null) {
                    long remaining = existing.endTime - System.currentTimeMillis();
                    if (remaining > 0) {
                        // A session is already running: keep it instead of starting a second one.
                        activeSessionId = existing.id;
                        beginCountdown(remaining);
                        return;
                    }
                    // Expired but never closed (service died earlier). Close it and continue
                    // to create the session the user actually asked for.
                    FocusSessionManager.completeSession(getApplicationContext(), existing);
                }

                long now = System.currentTimeMillis();
                long endTime = now + (durationMins * 60 * 1000L);
                FocusSessionEntity session = new FocusSessionEntity(now, endTime, (int) durationMins);
                activeSessionId = (int) db.focusSessionDao().insert(session);

                beginCountdown(durationMins * 60 * 1000L);
            } catch (Exception e) {
                Log.e(TAG, "[FOCUS] failed to start session", e);
                stopEverything();
            }
        }).start();
    }

    private void recoverSession() {
        new Thread(() -> {
            try {
                AppDatabase db = AppDatabase.getDatabase(getApplicationContext());
                FocusSessionEntity active = db.focusSessionDao().getActiveSession();

                if (active == null) {
                    // Nothing to run: a stale start (e.g. the query from the UI).
                    stopEverything();
                    return;
                }

                long remaining = active.endTime - System.currentTimeMillis();
                if (remaining <= 0) {
                    FocusSessionManager.completeSession(getApplicationContext(), active);
                    sendFinishedBroadcast(FocusSessionManager.calculateXp(active.emergencyUnlocksUsed));
                    stopEverything();
                    return;
                }

                activeSessionId = active.id;
                beginCountdown(remaining);
            } catch (Exception e) {
                Log.e(TAG, "[FOCUS] failed to recover session", e);
                stopEverything();
            }
        }).start();
    }

    private void beginCountdown(final long millis) {
        mainHandler.post(() -> {
            timeLeftInMillis = millis;
            sendTickBroadcast(timeLeftInMillis);
            startForegroundSafely("Focus session active");
            startTimer();
        });
    }

    private void startTimer() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        isRunning = true;
        running = true;

        countDownTimer = new CountDownTimer(timeLeftInMillis, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                timeLeftInMillis = millisUntilFinished;
                lastKnownTimeLeft = millisUntilFinished;
                sendTickBroadcast(timeLeftInMillis);
            }

            @Override
            public void onFinish() {
                timeLeftInMillis = 0;
                lastKnownTimeLeft = 0;
                isRunning = false;
                finishSession();
            }
        }.start();
    }

    /**
     * Ends the session using the shared manager so XP is awarded exactly once, even if the
     * guardian service or the accessibility service already closed it.
     */
    private void finishSession() {
        new Thread(() -> {
            int xpEarned = 0;
            try {
                FocusSessionEntity active = FocusSessionManager.getActiveSession(getApplicationContext());
                if (active != null) {
                    xpEarned = FocusSessionManager.calculateXp(active.emergencyUnlocksUsed);
                    if (!FocusSessionManager.completeSession(getApplicationContext(), active)) {
                        xpEarned = 0;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "[FOCUS] failed to finish session", e);
            }

            sendFinishedBroadcast(xpEarned);
            stopEverything();
        }).start();
    }

    private void stopEverything() {
        running = false;
        isRunning = false;
        mainHandler.post(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
            } catch (Throwable ignored) {
            }
            stopSelf();
        });
    }

    // ------------------------------------------------------------------------------------
    // Broadcasts / notification
    // ------------------------------------------------------------------------------------

    private void sendTickBroadcast(long millis) {
        Intent intent = new Intent(ACTION_TIMER_TICK);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_TIME_LEFT, millis);
        intent.putExtra(EXTRA_IS_RUNNING, isRunning);
        sendBroadcast(intent);
    }

    private void sendFinishedBroadcast(int xpEarned) {
        Intent intent = new Intent(ACTION_TIMER_FINISHED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_XP_EARNED, xpEarned);
        sendBroadcast(intent);
    }

    private void startForegroundSafely(String contentText) {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(contentText));
        } catch (Throwable t) {
            Log.e(TAG, "[FOCUS] startForeground failed", t);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Focus Timer Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String contentText) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SelfPilot Focus Session")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Keep the UI snapshot honest: the session is still ACTIVE in the database and
        // FocusSessionManager.syncState() will close it once endTime passes.
        running = false;
        isRunning = false;
        lastKnownTimeLeft = 0L;
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
