package com.example.selfpilot.util;

import android.app.usage.UsageStats;
import android.content.Context;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.AppInfoEntity;
import com.example.selfpilot.data.FocusSessionEntity;
import com.example.selfpilot.data.FrozenAppEntity;
import com.example.selfpilot.data.RestrictionRuleEntity;

import java.util.Calendar;
import java.util.Map;

public class RestrictionChecker {

    /**
     * Checks if a package is currently restricted according to its freeze state, limit, schedule, or Focus Mode.
     * Note: This method accesses the database and should be called from a background thread.
     */
    public static boolean isAppRestricted(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;

        // Do not block SelfPilot or critical Android system components
        if (packageName.equals(context.getPackageName()) ||
                packageName.equals("android") ||
                packageName.equals("com.android.systemui") ||
                packageName.contains("launcher") ||
                packageName.contains("settings")) {
            return false;
        }

        AppDatabase db = AppDatabase.getDatabase(context.getApplicationContext());
        RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(packageName);
        FrozenAppEntity frozen = db.frozenAppDao().getFrozenApp(packageName);

        long now = System.currentTimeMillis();
        if (rule != null && now < rule.unlockedUntil) {
            // Emergency unlocked - unconditionally allow
            return false;
        }

        boolean shouldBlock = (rule != null && rule.isBlocked) || (frozen != null && frozen.isFrozen);

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

        return shouldBlock;
    }
}
