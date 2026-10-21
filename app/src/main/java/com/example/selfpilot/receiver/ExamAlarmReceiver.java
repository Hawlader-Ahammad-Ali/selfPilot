package com.example.selfpilot.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.ExamProfileEntity;
import com.example.selfpilot.data.HonorScoreDao;
import com.example.selfpilot.data.HonorScoreEntity;
import com.example.selfpilot.util.ScoreCalculator;

import java.util.Calendar;
import java.util.concurrent.Executors;

public class ExamAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_EXAM_START = "com.example.selfpilot.ACTION_EXAM_START";
    public static final String ACTION_EXAM_END = "com.example.selfpilot.ACTION_EXAM_END";
    public static final String EXTRA_EXAM_ID = "EXTRA_EXAM_ID";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        
        int examId = intent.getIntExtra(EXTRA_EXAM_ID, -1);
        if (examId == -1) return;

        Executors.newSingleThreadExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getDatabase(context.getApplicationContext());
            ExamProfileEntity exam = db.examProfileDao().getExamById(examId);

            if (exam == null) return;

            if (ACTION_EXAM_START.equals(intent.getAction())) {
                if ("PENDING".equals(exam.status)) {
                    exam.isActive = true;
                    exam.status = "ACTIVE";
                    db.examProfileDao().update(exam);
                    Log.i("ExamAlarmReceiver", "Exam Mode Started: " + exam.examName);
                }
            } else if (ACTION_EXAM_END.equals(intent.getAction())) {
                if ("ACTIVE".equals(exam.status)) {
                    exam.isActive = false;
                    exam.status = "COMPLETED";
                    exam.xpEarned = Math.max(0, 20 - (5 * exam.emergencyUnlocksUsed));
                    db.examProfileDao().update(exam);
                    Log.i("ExamAlarmReceiver", "Exam Mode Completed: " + exam.examName + " XP: " + exam.xpEarned);

                    // Add XP to today's Honor Score immediately
                    Calendar calendar = Calendar.getInstance();
                    calendar.set(Calendar.HOUR_OF_DAY, 0);
                    calendar.set(Calendar.MINUTE, 0);
                    calendar.set(Calendar.SECOND, 0);
                    calendar.set(Calendar.MILLISECOND, 0);
                    long startOfDay = calendar.getTimeInMillis();

                    HonorScoreDao honorScoreDao = db.honorScoreDao();
                    HonorScoreEntity score = honorScoreDao.getScoreByDate(startOfDay);
                    if (score != null) {
                        score.setStudyPoints(score.getStudyPoints() + exam.xpEarned);
                        honorScoreDao.update(score);
                    } else {
                        // Force a recalculation if the entity doesn't exist yet today
                        ScoreCalculator.calculateAndSaveDailyScore(context.getApplicationContext());
                        score = honorScoreDao.getScoreByDate(startOfDay);
                        if (score != null) {
                            score.setStudyPoints(score.getStudyPoints() + exam.xpEarned);
                            honorScoreDao.update(score);
                        }
                    }
                }
            }
        });
    }
}
