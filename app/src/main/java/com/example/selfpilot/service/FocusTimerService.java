package com.example.selfpilot.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.selfpilot.R;

public class FocusTimerService extends Service {

    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_PAUSE = "ACTION_PAUSE";
    public static final String ACTION_RESUME = "ACTION_RESUME";
    public static final String ACTION_CANCEL = "ACTION_CANCEL";
    public static final String ACTION_QUERY_STATUS = "ACTION_QUERY_STATUS";

    public static final String EXTRA_DURATION = "EXTRA_DURATION";

    public static final String ACTION_TIMER_TICK = "com.example.selfpilot.TIMER_TICK";
    public static final String ACTION_TIMER_FINISHED = "com.example.selfpilot.TIMER_FINISHED";
    public static final String EXTRA_TIME_LEFT = "EXTRA_TIME_LEFT";
    public static final String EXTRA_IS_RUNNING = "EXTRA_IS_RUNNING";
    public static final String EXTRA_IS_PAUSED = "EXTRA_IS_PAUSED";

    private static final String CHANNEL_ID = "FocusTimerChannel";
    private static final int NOTIFICATION_ID = 1001;

    private CountDownTimer countDownTimer;
    private long timeLeftInMillis;
    private long initialDurationInMillis;
    private boolean isRunning = false;
    private boolean isPaused = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            switch (action) {
                case ACTION_START:
                    long durationMins = intent.getIntExtra(EXTRA_DURATION, 25);
                    initialDurationInMillis = durationMins * 60 * 1000L;
                    timeLeftInMillis = initialDurationInMillis;
                    startTimer();
                    startForeground(NOTIFICATION_ID, buildNotification("Focus Session Started"));
                    break;
                case ACTION_PAUSE:
                    pauseTimer();
                    break;
                case ACTION_RESUME:
                    startTimer();
                    break;
                case ACTION_CANCEL:
                    cancelTimer();
                    stopForeground(true);
                    stopSelf();
                    break;
                case ACTION_QUERY_STATUS:
                    sendTickBroadcast(timeLeftInMillis);
                    break;
            }
        }
        return START_STICKY;
    }

    private void startTimer() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        isRunning = true;
        isPaused = false;

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
                isPaused = false;
                sendFinishedBroadcast();
                stopForeground(true);
                stopSelf();
            }
        }.start();
    }

    private void pauseTimer() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        isRunning = true;
        isPaused = true;
        sendTickBroadcast(timeLeftInMillis);
    }

    private void cancelTimer() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        isRunning = false;
        isPaused = false;
        timeLeftInMillis = initialDurationInMillis;
        sendTickBroadcast(timeLeftInMillis);
    }

    private void sendTickBroadcast(long millis) {
        Intent intent = new Intent(ACTION_TIMER_TICK);
        intent.putExtra(EXTRA_TIME_LEFT, millis);
        intent.putExtra(EXTRA_IS_RUNNING, isRunning);
        intent.putExtra(EXTRA_IS_PAUSED, isPaused);
        sendBroadcast(intent);
    }

    private void sendFinishedBroadcast() {
        Intent intent = new Intent(ACTION_TIMER_FINISHED);
        intent.putExtra(EXTRA_DURATION, (int) (initialDurationInMillis / (60 * 1000L)));
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
