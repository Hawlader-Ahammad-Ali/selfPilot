package com.example.selfpilot.util;

import android.app.usage.UsageStats;
import android.content.Context;
import android.util.Log;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.AppInfoEntity;
import com.example.selfpilot.data.ExamProfileEntity;
import com.example.selfpilot.data.FocusSessionEntity;
import com.example.selfpilot.data.RestrictionRuleEntity;

import java.util.Calendar;
import java.util.List;
import java.util.Map;

public class RestrictionChecker {

    private static final String TAG = "SELF_PILOT_FREEZER";

    /**
     * Fast check: returns true ONLY if the package is currently marked as frozen.
     * Evaluates against FrozenAppsManager in-memory set (0ms).
     */
    public static boolean isAppFrozenOnly(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        boolean result = FrozenAppsManager.isFrozen(context, packageName);
        Log.d(TAG, "[CHECKER] isAppFrozenOnly(" + packageName + ") = " + result);
        return result;
    }

    /**
     * Checks if a package is currently restricted according to its freeze state, limit, schedule, Focus Mode, or Exam Mode.
     * Note: This method accesses the database and should be called from a background thread.
     */
    public static boolean isAppRestricted(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;

        // Do not block SelfPilot or critical Android system components
        if (packageName.equals(context.getPackageName()) ||
                packageName.equals("android") ||
                packageName.equals("com.android.systemui") ||
                packageName.contains("launcher") ||
                packageName.contains("settings") ||
                packageName.contains("dialer") ||
                packageName.contains("incall")) {
            return false;
        }

        // 1. Frozen apps are unconditionally blocked
        if (FrozenAppsManager.isFrozen(context, packageName)) {
            Log.i(TAG, "[CHECKER] isAppRestricted(" + packageName + ") → FROZEN (unconditional block)");
            return true;
        }

        AppDatabase db = AppDatabase.getDatabase(context.getApplicationContext());
        RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(packageName);
        long now = System.currentTimeMillis();
        if (rule != null && now < rule.unlockedUntil) {
            // Emergency unlocked - unconditionally allow
            Log.i(TAG, "[CHECKER] isAppRestricted(" + packageName + ") → EMERGENCY UNLOCKED until " + rule.unlockedUntil);
            return false;
        }

        boolean shouldBlock = (rule != null && rule.isBlocked);

        // Check Exam Mode logic
        if (!shouldBlock) {
            List<ExamProfileEntity> activeExams = db.examProfileDao().getActiveExams();
            if (activeExams != null && !activeExams.isEmpty()) {
                boolean isRestrictedByAnyExam = false;
                for (ExamProfileEntity exam : activeExams) {
                    if (exam.restrictedAppsCsv != null && exam.restrictedAppsCsv.contains(packageName)) {
                        isRestrictedByAnyExam = true;
                        break;
                    }
                }
                if (isRestrictedByAnyExam) {
                    shouldBlock = true;
                }
            }
        }

        // Check Focus Mode active
        if (!shouldBlock) {
            FocusSessionEntity activeSession = db.focusSessionDao().getActiveSession();
            if (activeSession != null) {
                AppInfoEntity appInfo = db.appInfoDao().getAppInfo(packageName);
                // Block if it's not explicitly marked as a Study App
                if (appInfo == null || !appInfo.isStudyApp) {
                    shouldBlock = true;
                }
            }
        }

        // Check Daily Limit Exceeded
        if (!shouldBlock && rule != null && rule.limitMinutes > 0) {
            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            long startOfDay = calendar.getTimeInMillis();

            Map<String, UsageStats> statsMap = UsageUtil.getUsageStats(context, startOfDay, now);
            if (statsMap != null && statsMap.containsKey(packageName)) {
                UsageStats stats = statsMap.get(packageName);
                if (stats != null) {
                    long usedMins = stats.getTotalTimeInForeground() / 60000;
                    if (usedMins >= rule.limitMinutes) {
                        shouldBlock = true;
                    }
                }
            }
        }

        // Check Scheduled Restrictions
        if (!shouldBlock && rule != null && rule.startTimeMinutes >= 0 && rule.endTimeMinutes >= 0) {
            Calendar c = Calendar.getInstance();
            int currentMins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            int startMins = rule.startTimeMinutes;
            int endMins = rule.endTimeMinutes;

            if (startMins > endMins) {
                // Overnight schedule (e.g. 21:00 to 08:00)
                if (currentMins >= startMins || currentMins <= endMins) {
                    shouldBlock = true;
                }
            } else {
                // Same day schedule (e.g. 09:00 to 17:00)
                if (currentMins >= startMins && currentMins <= endMins) {
                    shouldBlock = true;
                }
            }
        }

        Log.d(TAG, "[CHECKER] isAppRestricted(" + packageName + ") = " + shouldBlock);
        return shouldBlock;
    }

    public static boolean isAppEmergencyUnlocked(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        AppDatabase db = AppDatabase.getDatabase(context.getApplicationContext());
        RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(packageName);
        long now = System.currentTimeMillis();
        return rule != null && now < rule.unlockedUntil;
    }
}
