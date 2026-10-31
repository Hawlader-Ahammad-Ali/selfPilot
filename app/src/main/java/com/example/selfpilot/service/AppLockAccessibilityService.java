package com.example.selfpilot.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.example.selfpilot.ui.block.BlockActivity;
import com.example.selfpilot.util.FrozenAppsManager;
import com.example.selfpilot.util.RestrictionChecker;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Accessibility service that detects foreground app changes and enforces App Freezer restrictions.
 */
public class AppLockAccessibilityService extends AccessibilityService {

    private static final String TAG = "SELF_PILOT_FREEZER";

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> knownLaunchers = new HashSet<>();

    private String lastEnforcedPackage = null;
    private long lastEnforcedTime = 0;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "[LIFECYCLE] AccessibilityService CONNECTED");

        // Initialize the frozen apps in-memory cache immediately
        FrozenAppsManager.init(this);

        // Dynamically discover all registered launcher / home packages
        updateLauncherPackages();

        // Subscribe to both window state and window hierarchy changes
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.notificationTimeout = 20;
            setServiceInfo(info);
            Log.i(TAG, "[LIFECYCLE] Service config updated: TYPE_WINDOW_STATE_CHANGED | TYPE_WINDOWS_CHANGED");
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "[LIFECYCLE] AccessibilityService INTERRUPTED");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.w(TAG, "[LIFECYCLE] AccessibilityService DESTROYED");
        bgExecutor.shutdown();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }

        String eventPackage = extractPackageName(event);

        if (eventPackage == null || eventPackage.isEmpty()) {
            return;
        }

        // Skip SelfPilot itself and known safe system packages / launchers
        if (isSafePackage(eventPackage)) {
            return;
        }

        // Check if the foreground application is frozen (0ms in-memory check)
        boolean isFrozen = FrozenAppsManager.isFrozen(this, eventPackage);

        Log.d(TAG, "[EVENT] type=" + AccessibilityEvent.eventTypeToString(eventType)
                + " pkg=" + eventPackage + " isFrozen=" + isFrozen);

        if (isFrozen) {
            enforceBlock(eventPackage);
        } else {
            // Check non-freeze restrictions (daily limit, exam mode, focus session) on background thread
            bgExecutor.execute(() -> {
                boolean isRestricted = RestrictionChecker.isAppRestricted(getApplicationContext(), eventPackage);
                if (isRestricted) {
                    enforceBlock(eventPackage);
                }
            });
        }
    }

    /**
     * Extracts the true foreground package name from an accessibility event,
     * handling null package names common with TYPE_WINDOWS_CHANGED.
     */
    private String extractPackageName(AccessibilityEvent event) {
        // 1. Direct package from event
        CharSequence pkgSeq = event.getPackageName();
        if (pkgSeq != null && pkgSeq.length() > 0) {
            String pkg = pkgSeq.toString();
            if (!"android".equals(pkg)) {
                return pkg;
            }
        }

        // 2. Fallback to event source node
        try {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                CharSequence sourcePkg = source.getPackageName();
                if (sourcePkg != null && sourcePkg.length() > 0 && !"android".equals(sourcePkg.toString())) {
                    return sourcePkg.toString();
                }
            }
        } catch (Exception ignored) {}

        // 3. Fallback to active window root node
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                CharSequence rootPkg = root.getPackageName();
                if (rootPkg != null && rootPkg.length() > 0 && !"android".equals(rootPkg.toString())) {
                    return rootPkg.toString();
                }
            }
        } catch (Exception ignored) {}

        // 4. Fallback to focused window in window list
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window.isFocused() && window.getRoot() != null) {
                        AccessibilityNodeInfo windowRoot = window.getRoot();
                        if (windowRoot != null) {
                            CharSequence windowPkg = windowRoot.getPackageName();
                            if (windowPkg != null && windowPkg.length() > 0 && !"android".equals(windowPkg.toString())) {
                                return windowPkg.toString();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return pkgSeq != null ? pkgSeq.toString() : null;
    }

    /**
     * Brings BlockActivity to the foreground immediately over the target app.
     * Uses FLAG_ACTIVITY_REORDER_TO_FRONT + FLAG_ACTIVITY_NO_ANIMATION for instantaneous,
     * zero-flicker coverage. Throttles only micro-bursts (<150ms) to merge duplicate window events.
     */
    private void enforceBlock(String packageName) {
        long now = SystemClock.uptimeMillis();
        if (packageName.equals(lastEnforcedPackage) && (now - lastEnforcedTime < 150)) {
            // Skip within 150ms of the same burst
            return;
        }
        lastEnforcedPackage = packageName;
        lastEnforcedTime = now;

        Log.i(TAG, "[ENFORCE] Target is FROZEN/RESTRICTED → launching BlockActivity for " + packageName);

        try {
            Intent intent = new Intent(this, BlockActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.putExtra(BlockActivity.EXTRA_PACKAGE_NAME, packageName);
            startActivity(intent);
            Log.i(TAG, "[ENFORCE] BlockActivity launch attempted successfully for " + packageName);
        } catch (Exception e) {
            Log.e(TAG, "[ENFORCE] Failed to launch BlockActivity for " + packageName, e);
        }
    }

    /**
     * Queries PackageManager for all packages handling CATEGORY_HOME.
     */
    private void updateLauncherPackages() {
        try {
            PackageManager pm = getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);

            List<ResolveInfo> list = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (list != null) {
                for (ResolveInfo info : list) {
                    if (info.activityInfo != null && info.activityInfo.packageName != null) {
                        knownLaunchers.add(info.activityInfo.packageName);
                    }
                }
            }

            List<ResolveInfo> allList = pm.queryIntentActivities(intent, 0);
            if (allList != null) {
                for (ResolveInfo info : allList) {
                    if (info.activityInfo != null && info.activityInfo.packageName != null) {
                        knownLaunchers.add(info.activityInfo.packageName);
                    }
                }
            }
            Log.d(TAG, "[LAUNCHERS] Discovered home launchers: " + knownLaunchers);
        } catch (Exception e) {
            Log.e(TAG, "[LAUNCHERS] Error querying home launchers", e);
        }
    }

    /**
     * Determines whether a package should never be blocked.
     */
    private boolean isSafePackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return true;
        if (packageName.equals(getPackageName())) return true;
        if ("android".equals(packageName) || "com.android.systemui".equals(packageName)) return true;
        if (knownLaunchers.contains(packageName)) return true;
        if (packageName.contains("launcher") || packageName.contains("dialer") || packageName.contains("incall")) return true;
        if ("com.android.settings".equals(packageName) || packageName.endsWith(".settings")) return true;
        return false;
    }
}
