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

    /** Why an app is currently blocked. Drives the message shown on the block surface. */
    public enum BlockReason {
        NONE,
        FROZEN,
        DAILY_LIMIT,
        FOCUS_MODE,
        EXAM_MODE,
        SCHEDULE,
        RULE_BLOCKED
    }

    /** Result of evaluating a package, including the numbers needed for a useful message. */
    public static class BlockDecision {
        public final BlockReason reason;
        public final int limitMinutes;
        public final int usedMinutes;
        public final int emergencyUnlocksLeft;

        private BlockDecision(BlockReason reason, int limitMinutes, int usedMinutes, int unlocksLeft) {
            this.reason = reason;
            this.limitMinutes = limitMinutes;
            this.usedMinutes = usedMinutes;
            this.emergencyUnlocksLeft = unlocksLeft;
        }

        public static BlockDecision allow() {
            return new BlockDecision(BlockReason.NONE, 0, 0, 0);
        }

        private static BlockDecision blocked(BlockReason reason, int limitMinutes, int usedMinutes,
                                             int unlocksLeft) {
            return new BlockDecision(reason, limitMinutes, usedMinutes, unlocksLeft);
        }

        public boolean isBlocked() {
            return reason != BlockReason.NONE;
        }

        public boolean isFrozen() {
            return reason == BlockReason.FROZEN;
        }
    }

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
     * Backwards compatible boolean form of {@link #evaluate(Context, String)}.
     * Note: this method hits the database and must be called from a background thread.
     */
    public static boolean isAppRestricted(Context context, String packageName) {
        return evaluate(context, packageName).isBlocked();
    }

    /**
     * Evaluates every restriction that applies to a package right now: freeze state,
     * daily usage limit, Focus Mode, Exam Mode, schedule and manual rule.
     * Must be called from a background thread.
     */
    public static BlockDecision evaluate(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return BlockDecision.allow();

        // SelfPilot and critical system components are never restricted.
        if (packageName.equals(context.getPackageName())
                || "android".equals(packageName)
                || "com.android.systemui".equals(packageName)
                || packageName.contains("launcher")
                || packageName.endsWith(".settings")
                || packageName.contains("dialer")
                || packageName.contains("incall")) {
            return BlockDecision.allow();
        }

        int unlocksLeft = EmergencyUnlockManager.getRemainingUnlocks(context);

        // 1. Frozen apps are unconditionally blocked.
        if (FrozenAppsManager.isFrozen(context, packageName)) {
            return BlockDecision.blocked(BlockReason.FROZEN, 0, 0, 0);
        }

        AppDatabase db = AppDatabase.getDatabase(context.getApplicationContext());
        RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(packageName);
        long now = System.currentTimeMillis();

        if (rule != null && now < rule.unlockedUntil) {
            // Emergency unlock still valid.
            return BlockDecision.allow();
        }

        // 2. Manual block rule.
        if (rule != null && rule.isBlocked) {
            return BlockDecision.blocked(BlockReason.RULE_BLOCKED, rule.limitMinutes, 0, unlocksLeft);
        }

        // 3. Exam Mode.
        List<ExamProfileEntity> activeExams = db.examProfileDao().getActiveExams();
        if (activeExams != null) {
            for (ExamProfileEntity exam : activeExams) {
                if (containsPackage(exam.restrictedAppsCsv, packageName)) {
                    return BlockDecision.blocked(BlockReason.EXAM_MODE, 0, 0, unlocksLeft);
                }
            }
        }

        // 4. Focus Mode: everything except study apps is blocked.
        FocusSessionEntity activeSession = db.focusSessionDao().getActiveSession();
        if (activeSession != null) {
            AppInfoEntity appInfo = db.appInfoDao().getAppInfo(packageName);
            if (appInfo == null || !appInfo.isStudyApp) {
                return BlockDecision.blocked(BlockReason.FOCUS_MODE, 0, 0, unlocksLeft);
            }
        }

        // 5. Daily limit.
        if (rule != null && rule.limitMinutes > 0) {
            long startOfDay = getStartOfDay();
            Map<String, UsageStats> statsMap = UsageUtil.getUsageStats(context, startOfDay, now);
            int usedMinutes = 0;
            if (statsMap != null) {
                UsageStats stats = statsMap.get(packageName);
                if (stats != null) {
                    usedMinutes = (int) (stats.getTotalTimeInForeground() / 60000L);
                }
            }
            if (usedMinutes >= rule.limitMinutes) {
                Log.i(TAG, "[CHECKER] Daily limit reached for " + packageName
                        + " (" + usedMinutes + "/" + rule.limitMinutes + " min)");
                return BlockDecision.blocked(BlockReason.DAILY_LIMIT,
                        rule.limitMinutes, usedMinutes, unlocksLeft);
            }
        }

        // 6. Scheduled block window.
        if (rule != null && rule.startTimeMinutes >= 0 && rule.endTimeMinutes >= 0) {
            Calendar c = Calendar.getInstance();
            int currentMins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            int startMins = rule.startTimeMinutes;
            int endMins = rule.endTimeMinutes;

            boolean inWindow;
            if (startMins > endMins) {
                // Overnight schedule (e.g. 21:00 -> 08:00).
                inWindow = currentMins >= startMins || currentMins <= endMins;
            } else {
                inWindow = currentMins >= startMins && currentMins <= endMins;
            }
            if (inWindow) {
                return BlockDecision.blocked(BlockReason.SCHEDULE, 0, 0, unlocksLeft);
            }
        }

        return BlockDecision.allow();
    }

    public static boolean isAppEmergencyUnlocked(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        AppDatabase db = AppDatabase.getDatabase(context.getApplicationContext());
        RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(packageName);
        long now = System.currentTimeMillis();
        return rule != null && now < rule.unlockedUntil;
    }

    /**
     * Exact membership test for a comma separated package list.
     * A plain String.contains() would treat "com.foo" as present inside "com.foobar".
     */
    public static boolean containsPackage(String csv, String packageName) {
        if (csv == null || csv.isEmpty() || packageName == null || packageName.isEmpty()) return false;
        for (String entry : csv.split(",")) {
            if (entry.trim().equals(packageName)) return true;
        }
        return false;
    }

    private static long getStartOfDay() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
