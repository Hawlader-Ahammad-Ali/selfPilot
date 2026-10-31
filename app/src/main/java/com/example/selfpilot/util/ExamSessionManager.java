package com.example.selfpilot.util;

import android.content.Context;
import android.util.Log;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.ExamProfileEntity;

import java.util.List;

/**
 * Keeps exam profiles in sync with the clock.
 *
 * Exam Mode used to depend entirely on two AlarmManager alarms. If an alarm was missed
 * (device off, reboot before boot receiver ran, Doze batching, exact-alarm permission
 * denied, alarm cancelled by a task killer) the exam either never activated, or stayed
 * ACTIVE forever and kept blocking apps long after the exam period ended.
 *
 * This class makes the stored state self-healing: it is derived from startDate/endDate,
 * so every entry point (boot, guardian tick, accessibility tick, alarm) converges to the
 * same correct state.
 */
public class ExamSessionManager {

    private static final String TAG = "SELF_PILOT_FREEZER";

    /**
     * Applies time based transitions. Safe to call often and from any thread.
     *
     * @return true when a profile changed state (useful to trigger a score recalculation).
     */
    public static boolean syncState(Context context) {
        if (context == null) return false;
        Context appContext = context.getApplicationContext();
        boolean changed = false;

        try {
            AppDatabase db = AppDatabase.getDatabase(appContext);
            List<ExamProfileEntity> exams = db.examProfileDao().getAllExams();
            if (exams == null || exams.isEmpty()) return false;

            long now = System.currentTimeMillis();

            for (ExamProfileEntity exam : exams) {
                if (exam == null || exam.status == null) continue;

                boolean isPending = "PENDING".equals(exam.status);
                boolean isActive = "ACTIVE".equals(exam.status) || exam.isActive;
                if (!isPending && !isActive) continue;

                if (now > exam.endDate) {
                    // Exam period is over. Award XP only if it actually ran.
                    if (isActive) {
                        exam.xpEarned = Math.max(0, 20 - (5 * exam.emergencyUnlocksUsed));
                    }
                    exam.status = "COMPLETED";
                    exam.isActive = false;
                    db.examProfileDao().update(exam);
                    changed = true;
                    Log.i(TAG, "[EXAM] Completed" + (isActive ? " (XP " + exam.xpEarned + ")" : "")
                            + ": " + exam.examName);
                } else if (isPending && now >= exam.startDate) {
                    // Start alarm was missed or fired late - activate now.
                    exam.status = "ACTIVE";
                    exam.isActive = true;
                    db.examProfileDao().update(exam);
                    changed = true;
                    Log.i(TAG, "[EXAM] Activated (catch-up): " + exam.examName);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "[EXAM] syncState failed", e);
        }

        return changed;
    }

    /** Convenience for callers that also need the score refreshed after a state change. */
    public static void syncStateAndRescore(Context context) {
        if (syncState(context)) {
            try {
                ScoreCalculator.calculateAndSaveDailyScore(context.getApplicationContext());
            } catch (Exception e) {
                Log.w(TAG, "[EXAM] score recalculation failed", e);
            }
        }
    }
}
