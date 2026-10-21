package com.example.selfpilot.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.ExamProfileEntity;

import java.util.concurrent.Executors;

public class ExamAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_EXAM_START = "com.example.selfpilot.EXAM_START";
    public static final String ACTION_EXAM_END = "com.example.selfpilot.EXAM_END";
    public static final String EXTRA_EXAM_ID = "EXTRA_EXAM_ID";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        
        int examId = intent.getIntExtra(EXTRA_EXAM_ID, -1);
        if (examId == -1) return;

        Executors.newSingleThreadExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getDatabase(context.getApplicationContext());
            ExamProfileEntity exam = db.examProfileDao().getExamById(examId);

            if (exam != null) {
                if (ACTION_EXAM_START.equals(intent.getAction())) {
                    exam.isActive = true;
                    exam.status = "ACTIVE";
                    db.examProfileDao().update(exam);
                    Log.i("ExamAlarmReceiver", "Exam Mode Activated: " + exam.examName);
                } else if (ACTION_EXAM_END.equals(intent.getAction())) {
                    exam.isActive = false;
                    exam.status = "COMPLETED";
                    
                    // Simple XP Reward allocation base logic
                    int emergencyUnlocks = exam.emergencyUnlocksUsed;
                    int baseReward = 20;
                    int finalXp = Math.max(0, baseReward - (5 * emergencyUnlocks));
                    exam.xpEarned = finalXp;

                    db.examProfileDao().update(exam);
                    Log.i("ExamAlarmReceiver", "Exam Mode Completed: " + exam.examName + " | Earned: " + finalXp + " XP");

                    // TODO: The global HonorScore recalculation routine will sweep and absorb this XP into the daily total on the next update.
                }
            }
        });
    }
}
