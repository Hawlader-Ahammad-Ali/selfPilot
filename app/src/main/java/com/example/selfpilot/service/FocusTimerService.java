package com.example.selfpilot.service;

import android.app.Service;
import android.content.Intent;
import android.os.CountDownTimer;
import android.os.IBinder;

import androidx.annotation.Nullable;

public class FocusTimerService extends Service {

    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_PAUSE = "ACTION_PAUSE";
    public static final String ACTION_RESUME = "ACTION_RESUME";
    public static final String ACTION_CANCEL = "ACTION_CANCEL";

    public static final String EXTRA_DURATION = "EXTRA_DURATION";

    public static final String ACTION_TIMER_TICK = "com.example.selfpilot.TIMER_TICK";
    public static final String ACTION_TIMER_FINISHED = "com.example.selfpilot.TIMER_FINISHED";
    public static final String EXTRA_TIME_LEFT = "EXTRA_TIME_LEFT";

    private CountDownTimer countDownTimer;
    private long timeLeftInMillis;
    private long initialDurationInMillis;

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
                    break;
                case ACTION_PAUSE:
                    pauseTimer();
                    break;
                case ACTION_RESUME:
                    startTimer();
                    break;
                case ACTION_CANCEL:
                    cancelTimer();
                    stopSelf();
                    break;
            }
        }
        return START_STICKY;
    }

    private void startTimer() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        countDownTimer = new CountDownTimer(timeLeftInMillis, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                timeLeftInMillis = millisUntilFinished;
                sendTickBroadcast(timeLeftInMillis);
            }

            @Override
            public void onFinish() {
                timeLeftInMillis = 0;
                sendFinishedBroadcast();
                stopSelf();
            }
        }.start();
    }

    private void pauseTimer() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }

    private void cancelTimer() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        timeLeftInMillis = initialDurationInMillis;
        sendTickBroadcast(timeLeftInMillis);
    }

    private void sendTickBroadcast(long millis) {
        Intent intent = new Intent(ACTION_TIMER_TICK);
        intent.putExtra(EXTRA_TIME_LEFT, millis);
        sendBroadcast(intent);
    }

    private void sendFinishedBroadcast() {
        Intent intent = new Intent(ACTION_TIMER_FINISHED);
        intent.putExtra(EXTRA_DURATION, (int) (initialDurationInMillis / (60 * 1000L)));
        sendBroadcast(intent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
