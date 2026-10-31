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
            info.notificationTimeout = 0;
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

        // 1. Direct event package check
        CharSequence pkgSeq = event.getPackageName();
        if (pkgSeq != null && pkgSeq.length() > 0) {
            String directPkg = pkgSeq.toString();
            if (isSafePackage(directPkg)) {
                return;
            }
            if (FrozenAppsManager.isFrozen(this, directPkg)) {
                Log.d(TAG, "[EVENT] Direct frozen match: " + directPkg);
                enforceBlock(directPkg);
                return;
            }
        }

        // 2. Window hierarchy inspection (critical for TYPE_WINDOWS_CHANGED and Android 12+ StartingWindow)
        String foregroundApp = findTopApplicationPackage();
        if (foregroundApp != null && !isSafePackage(foregroundApp)) {
            if (FrozenAppsManager.isFrozen(this, foregroundApp)) {
                Log.d(TAG, "[EVENT] Top window frozen match: " + foregroundApp);
                enforceBlock(foregroundApp);
                return;
            } else {
                // Check non-freeze restrictions on background thread
                final String pkgToCheck = foregroundApp;
                bgExecutor.execute(() -> {
                    if (RestrictionChecker.isAppRestricted(getApplicationContext(), pkgToCheck)) {
                        enforceBlock(pkgToCheck);
                    }
                });
                return;
            }
        }

        // 3. Fallback check for directPkg non-freeze restrictions
        if (pkgSeq != null && pkgSeq.length() > 0) {
            String directPkg = pkgSeq.toString();
            if (!"android".equals(directPkg) && !isSafePackage(directPkg)) {
                bgExecutor.execute(() -> {
                    if (RestrictionChecker.isAppRestricted(getApplicationContext(), directPkg)) {
                        enforceBlock(directPkg);
                    }
                });
            }
        }
    }

    /**
     * Inspects the current interactive windows in top-to-bottom Z-order
     * to identify the topmost application package.
     */
    private String findTopApplicationPackage() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null && !windows.isEmpty()) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        AccessibilityNodeInfo root = window.getRoot();
                        if (root != null) {
                            CharSequence rootPkg = root.getPackageName();
                            if (rootPkg != null && rootPkg.length() > 0) {
                                String pkg = rootPkg.toString();
                                if (!"android".equals(pkg)) {
                                    return pkg;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // Fallback to active window root node
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                CharSequence rootPkg = root.getPackageName();
                if (rootPkg != null && rootPkg.length() > 0) {
                    String pkg = rootPkg.toString();
                    if (!"android".equals(pkg)) {
                        return pkg;
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Enforces blocking for the frozen or restricted package.
     * Launches BlockActivity on top without dropping user taps.
     */
    private void enforceBlock(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        if (packageName.equals(getPackageName()) || "com.example.selfpilot".equals(packageName)) return;
        if (isSafePackage(packageName)) return;

        Log.i(TAG, "[ENFORCE] Target is FROZEN/RESTRICTED → launching BlockActivity for " + packageName);

        try {
            Intent intent = new Intent(this, BlockActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.putExtra(BlockActivity.EXTRA_PACKAGE_NAME, packageName);
            startActivity(intent);
            Log.i(TAG, "[ENFORCE] BlockActivity launch attempted for " + packageName);
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
        if (packageName.equals(getPackageName()) || "com.example.selfpilot".equals(packageName)) return true;
        if ("android".equals(packageName) || "com.android.systemui".equals(packageName)) return true;
        if (knownLaunchers.contains(packageName)) return true;
        if (packageName.contains("launcher") || packageName.contains("dialer") || packageName.contains("incall")) return true;
        if ("com.android.settings".equals(packageName) || packageName.endsWith(".settings")) return true;
        return false;
    }
}
