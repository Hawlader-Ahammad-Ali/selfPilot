package com.example.selfpilot.service;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.RestrictionRuleEntity;
import com.example.selfpilot.util.RestrictionChecker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SelfPilotNotificationListener extends NotificationListenerService {

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        
        String packageName = sbn.getPackageName();
        if (packageName == null || packageName.equals(getPackageName())) return;

        executorService.execute(() -> {
            if (RestrictionChecker.isAppRestricted(this, packageName)) {
                AppDatabase db = AppDatabase.getDatabase(this);
                RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(packageName);
                if (rule != null && rule.suppressNotifications) {
                    cancelNotification(sbn.getKey());
                }
            }
        });
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Not used, but required to override
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executorService.shutdown();
    }
}
