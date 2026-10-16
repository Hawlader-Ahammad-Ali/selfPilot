package com.example.selfpilot.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.RestrictionRuleEntity;
import com.example.selfpilot.ui.block.BlockActivity;

public class AppLockAccessibilityService extends AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence packageNameSeq = event.getPackageName();
            if (packageNameSeq != null) {
                String packageName = packageNameSeq.toString();
                
                new Thread(() -> {
                    AppDatabase db = AppDatabase.getDatabase(getApplicationContext());
                    RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(packageName);
                    
                    if (rule != null && rule.isBlocked) {
                        if (System.currentTimeMillis() < rule.unlockedUntil) {
                            // Currently unlocked
                            return;
                        }
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
