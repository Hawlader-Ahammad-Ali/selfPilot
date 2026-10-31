package com.example.selfpilot.util;

import android.content.Context;
import android.util.Log;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.EmergencyUnlockEntity;
import com.example.selfpilot.data.ExamProfileEntity;
import com.example.selfpilot.data.FocusSessionEntity;
import com.example.selfpilot.data.RestrictionRuleEntity;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single implementation of the "5 minute emergency break" rules, shared by the block surface
 * and the legacy block screen so the daily counter can never drift between them.
 */
public class EmergencyUnlockManager {

    private static final String TAG = "SELF_PILOT_FREEZER";

    public static final int MAX_UNLOCKS_PER_DAY = 3;
    public static final long UNLOCK_DURATION_MS = 5 * 60 * 1000L;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface Callback {
        void onResult(boolean granted, int remaining);
    }

    /**
     * Blocking read of the remaining breaks for today. Safe on a background thread only.
     */
    public static int getRemainingUnlocks(Context context) {
        try {
            AppDatabase db = AppDatabase.getDatabase(context.getApplicationContext());
            EmergencyUnlockEntity entity = db.emergencyUnlockDao().getUnlockByDate(getTodayStart());
            int used = entity != null ? entity.unlocksUsed : 0;
            return Math.max(0, MAX_UNLOCKS_PER_DAY - used);
        } catch (Exception e) {
            Log.w(TAG, "[UNLOCK] Failed to read remaining unlocks", e);
            return 0;
        }
    }

    /**
     * Grants a 5 minute break for the package and applies the XP penalties.
     * The callback always runs on a background thread.
     */
    public static void requestUnlock(Context context, String packageName, Callback callback) {
        Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            boolean granted = false;
            int remaining = 0;
            try {
                AppDatabase db = AppDatabase.getDatabase(appContext);
                long todayStart = getTodayStart();

                EmergencyUnlockEntity entity = db.emergencyUnlockDao().getUnlockByDate(todayStart);
                if (entity == null) {
                    entity = new EmergencyUnlockEntity();
                    entity.date = todayStart;
                    entity.unlocksUsed = 0;
                    db.emergencyUnlockDao().insert(entity);
                    entity = db.emergencyUnlockDao().getUnlockByDate(todayStart);
                }

                if (entity != null && entity.unlocksUsed < MAX_UNLOCKS_PER_DAY) {
                    entity.unlocksUsed++;
                    db.emergencyUnlockDao().update(entity);

                    RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(packageName);
                    if (rule == null) {
                        rule = new RestrictionRuleEntity();
                        rule.packageName = packageName;
                        db.restrictionRuleDao().insert(rule);
                        rule = db.restrictionRuleDao().getRuleByPackageName(packageName);
                    }
                    if (rule != null) {
                        rule.unlockedUntil = System.currentTimeMillis() + UNLOCK_DURATION_MS;
                        db.restrictionRuleDao().update(rule);
                    }

                    FocusSessionEntity activeSession = db.focusSessionDao().getActiveSession();
                    if (activeSession != null) {
                        activeSession.emergencyUnlocksUsed++;
                        db.focusSessionDao().update(activeSession);
                    }

                    List<ExamProfileEntity> activeExams = db.examProfileDao().getActiveExams();
                    if (activeExams != null) {
                        for (ExamProfileEntity exam : activeExams) {
                            exam.emergencyUnlocksUsed++;
                            db.examProfileDao().update(exam);
                        }
                    }

                    granted = true;
                    Log.i(TAG, "[UNLOCK] Emergency break granted for " + packageName);
                }

                remaining = getRemainingUnlocks(appContext);
            } catch (Exception e) {
                Log.e(TAG, "[UNLOCK] Emergency unlock failed for " + packageName, e);
            }

            if (callback != null) {
                callback.onResult(granted, remaining);
            }
        });
    }

    private static long getTodayStart() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
