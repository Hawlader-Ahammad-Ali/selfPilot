package com.example.selfpilot.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import com.example.selfpilot.ui.block.BlockActivity;
import com.example.selfpilot.util.RestrictionChecker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppLockAccessibilityService extends AccessibilityService {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private String currentPackage = null;

    private final Runnable checkLimitRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentPackage != null) {
                checkPackageAndBlockIfRestricted(currentPackage);
                // Re-check after 60 seconds if the user stays in the app
                handler.postDelayed(this, 60000);
            }
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence packageNameSeq = event.getPackageName();
            if (packageNameSeq != null) {
                String packageName = packageNameSeq.toString();

                if (packageName.equals(getPackageName()) ||
                        packageName.equals("android") ||
                        packageName.equals("com.android.systemui") ||
                        packageName.contains("launcher") ||
                        packageName.contains("settings")) {
                    currentPackage = null;
                    handler.removeCallbacks(checkLimitRunnable);
                    return;
                }

                currentPackage = packageName;
                handler.removeCallbacks(checkLimitRunnable);
                
                // Check immediately on launch
                checkPackageAndBlockIfRestricted(currentPackage);
                
                // Start background poller to kick out if limit expires while inside the app
                handler.postDelayed(checkLimitRunnable, 60000);
            }
        }
    }

    private void checkPackageAndBlockIfRestricted(String packageName) {
        executorService.execute(() -> {
            boolean shouldBlock = RestrictionChecker.isAppRestricted(getApplicationContext(), packageName);

            if (shouldBlock) {
                // Return to main thread to launch activity safely
                handler.post(() -> {
                    Intent intent = new Intent(this, BlockActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    intent.putExtra("packageName", packageName);
                    startActivity(intent);
                });
            }
        });
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(checkLimitRunnable);
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(checkLimitRunnable);
        executorService.shutdown();
    }
}
