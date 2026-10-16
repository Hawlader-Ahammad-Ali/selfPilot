package com.example.selfpilot.service;

import android.accessibilityservice.AccessibilityService;
import android.app.usage.UsageStats;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.RestrictionRuleEntity;
import com.example.selfpilot.ui.block.BlockActivity;
import com.example.selfpilot.util.UsageUtil;

import java.util.Calendar;
import java.util.Map;

public class AppLockAccessibilityService extends AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence packageNameSeq = event.getPackageName();
            if (packageNameSeq != null) {
                String packageName = packageNameSeq.toString();

                // Do not block SelfPilot or critical Android system components
                if (packageName.equals(getPackageName()) ||
                        packageName.equals("android") ||
                        packageName.equals("com.android.systemui") ||
                        packageName.contains("launcher") ||
                        packageName.contains("settings")) {
                    return;
                }

                new Thread(() -> {
                    AppDatabase db = AppDatabase.getDatabase(getApplicationContext());
                    RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(packageName);

                    if (rule == null) return;

                    long now = System.currentTimeMillis();
                    if (now < rule.unlockedUntil) {
                        // Emergency unlocked
                        return;
                    }

                    boolean shouldBlock = rule.isBlocked;

                    // Check Daily Limit Exceeded
                    if (!shouldBlock && rule.limitMinutes > 0) {
                        Calendar calendar = Calendar.getInstance();
                        calendar.set(Calendar.HOUR_OF_DAY, 0);
                        calendar.set(Calendar.MINUTE, 0);
                        calendar.set(Calendar.SECOND, 0);
                        calendar.set(Calendar.MILLISECOND, 0);
                        long startOfDay = calendar.getTimeInMillis();

                        Map<String, UsageStats> statsMap = UsageUtil.getUsageStats(getApplicationContext(), startOfDay, now);
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
                    if (!shouldBlock && rule.startTimeMinutes >= 0 && rule.endTimeMinutes >= 0) {
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

                    if (shouldBlock) {
                        Intent intent = new Intent(this, BlockActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        intent.putExtra("packageName", packageName);
                        startActivity(intent);
                    }
                }).start();
            }
        }
    }

    @Override
    public void onInterrupt() {
    }
}
