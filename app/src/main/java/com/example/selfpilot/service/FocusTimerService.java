package com.example.selfpilot.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.FocusSessionEntity;
import com.example.selfpilot.util.ScoreCalculator;

public class FocusTimerService extends Service {

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

    private CountDownTimer countDownTimer;
    private long timeLeftInMillis;
    private int activeSessionId = -1;
    private boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                long durationMins = intent.getIntExtra(EXTRA_DURATION, 25);
                startNewSession(durationMins);
            } else if (ACTION_RECOVER.equals(action)) {
                recoverSession();
            } else if (ACTION_QUERY_STATUS.equals(action)) {
                sendTickBroadcast(timeLeftInMillis);
            }
        }
        return START_STICKY;
    }

    private void startNewSession(long durationMins) {
        new Thread(() -> {
            AppDatabase db = AppDatabase.getDatabase(getApplicationContext());
            FocusSessionEntity existing = db.focusSessionDao().getActiveSession();
            
            if (existing != null) {
                // Already active
                activeSessionId = existing.id;
                timeLeftInMillis = existing.endTime - System.currentTimeMillis();
                if (timeLeftInMillis > 0) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        startForeground(NOTIFICATION_ID, buildNotification("Focus Session Active. Stay Focused!"));
                        startTimer();
                    });
                } else {
                    finishSession();
                }
            } else {
                // Start completely new
                long now = System.currentTimeMillis();
                long endTime = now + (durationMins * 60 * 1000L);
                FocusSessionEntity session = new FocusSessionEntity(now, endTime, (int) durationMins);
                activeSessionId = (int) db.focusSessionDao().insert(session);
                timeLeftInMillis = endTime - now;
                
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    startForeground(NOTIFICATION_ID, buildNotification("Focus Session Active. Stay Focused!"));
                    startTimer();
                });
            }
        }).start();
    }

    private void recoverSession() {
        new Thread(() -> {
            AppDatabase db = AppDatabase.getDatabase(getApplicationContext());
            FocusSessionEntity existing = db.focusSessionDao().getActiveSession();
            
            if (existing != null) {
                activeSessionId = existing.id;
                timeLeftInMillis = existing.endTime - System.currentTimeMillis();
                
                if (timeLeftInMillis > 0) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        startForeground(NOTIFICATION_ID, buildNotification("Focus Session Active. Stay Focused!"));
                        startTimer();
                    });
                } else {
                    finishSession();
                }
            } else {
                stopSelf();
            }
        }).start();
    }

    private void startTimer() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        isRunning = true;

        // Run UI ticks on MainThread implicitly via CountDownTimer
        countDownTimer = new CountDownTimer(timeLeftInMillis, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                timeLeftInMillis = millisUntilFinished;
                sendTickBroadcast(timeLeftInMillis);
            }

            @Override
            public void onFinish() {
                timeLeftInMillis = 0;
                isRunning = false;
                finishSession();
            }
        }.start();
    }

    private void finishSession() {
        new Thread(() -> {
            int xpEarned = 0;
            AppDatabase db = AppDatabase.getDatabase(getApplicationContext());
            FocusSessionEntity session = db.focusSessionDao().getActiveSession();
            
            if (session != null) {
                session.status = "COMPLETED";
                xpEarned = Math.max(0, 20 - (5 * session.emergencyUnlocksUsed));
                session.xpEarned = xpEarned;
                db.focusSessionDao().update(session);
                
                // Trigger global score recalculation
                ScoreCalculator.calculateAndSaveDailyScore(getApplicationContext());
            }
            
            sendFinishedBroadcast(xpEarned);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            stopSelf();
        }).start();
    }

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
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
