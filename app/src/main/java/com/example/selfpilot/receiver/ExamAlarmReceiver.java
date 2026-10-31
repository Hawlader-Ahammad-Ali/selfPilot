package com.example.selfpilot.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.selfpilot.util.ExamSessionManager;

/**
 * Alarm entry point for Exam Mode.
 *
 * The alarm is only a wake-up nudge: the actual state transitions live in
 * {@link ExamSessionManager} and are derived from startDate/endDate. That way a missed,
 * duplicated or replayed alarm can never leave an exam stuck ACTIVE (apps blocked after
 * the exam ended) or PENDING (exam never starting).
 */
public class ExamAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_EXAM_START = "com.example.selfpilot.ACTION_EXAM_START";
    public static final String ACTION_EXAM_END = "com.example.selfpilot.ACTION_EXAM_END";
    public static final String EXTRA_EXAM_ID = "EXTRA_EXAM_ID";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        Log.i("ExamAlarmReceiver", "[EXAM] Alarm received: " + intent.getAction()
                + " (examId=" + intent.getIntExtra(EXTRA_EXAM_ID, -1) + ")");

        final Context appContext = context.getApplicationContext();
        final PendingResult pendingResult = goAsync();

        new Thread(() -> {
            try {
                ExamSessionManager.syncStateAndRescore(appContext);
            } catch (Throwable t) {
                Log.e("ExamAlarmReceiver", "[EXAM] alarm handling failed", t);
            } finally {
                pendingResult.finish();
            }
        }).start();
    }
}
