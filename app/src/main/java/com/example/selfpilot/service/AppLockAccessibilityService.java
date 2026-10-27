package com.example.selfpilot.service;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import com.example.selfpilot.ui.block.BlockActivity;
import com.example.selfpilot.util.RestrictionChecker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppLockAccessibilityService extends AccessibilityService {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private String currentPackage = null;
    
    private WindowManager windowManager;
    private View redOverlayView;
    private boolean isOverlayAdded = false;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

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
                    handler.post(this::removeRedOverlay);
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
            boolean isEmergencyUnlocked = false;

            if (!shouldBlock) {
                isEmergencyUnlocked = RestrictionChecker.isAppEmergencyUnlocked(getApplicationContext(), packageName);
            }

            if (shouldBlock) {
                // Return to main thread to launch activity safely
                handler.post(() -> {
                    removeRedOverlay();
                    Intent intent = new Intent(this, BlockActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    intent.putExtra("packageName", packageName);
                    startActivity(intent);
                });
            } else if (isEmergencyUnlocked) {
                handler.post(this::showRedOverlay);
            } else {
                handler.post(this::removeRedOverlay);
            }
        });
    }

    private void showRedOverlay() {
        if (isOverlayAdded || windowManager == null) return;

        redOverlayView = new View(this);
        redOverlayView.setBackgroundColor(android.graphics.Color.parseColor("#33FF0000")); // semi-transparent red

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        try {
            windowManager.addView(redOverlayView, params);
            isOverlayAdded = true;
        } catch (Exception ignored) {
        }
    }

    private void removeRedOverlay() {
        if (!isOverlayAdded || redOverlayView == null || windowManager == null) return;
        try {
            windowManager.removeView(redOverlayView);
            isOverlayAdded = false;
            redOverlayView = null;
        } catch (Exception ignored) {
        }
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
