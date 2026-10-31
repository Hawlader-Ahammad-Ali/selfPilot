package com.example.selfpilot.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.example.selfpilot.ui.block.BlockActivity;
import com.example.selfpilot.util.FrozenAppsManager;
import com.example.selfpilot.util.RestrictionChecker;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Accessibility service that enforces App Freezer restrictions.
 *
 * Why this class is more than an event handler:
 *
 * An app launch does not always produce a reliable {@link AccessibilityEvent}. Bringing an
 * already-created task to the front (launcher icon re-tap, recents, deep link into a warm
 * process) frequently emits only TYPE_WINDOWS_CHANGED with a null package name, and on some
 * OEM builds nothing at all. Relying on events alone is exactly why a frozen app could be
 * opened "sometimes", and why repeated taps eventually slipped through.
 *
 * Two independent detection layers are used instead:
 *   1. Event driven  - instant reaction to window state changes.
 *   2. Polling watchdog - resolves the real foreground package every 300 ms while the screen
 *      is on, so a missed event can never win the race.
 *
 * Every block is also verified and reinforced: the frozen activity can finish resuming a few
 * hundred milliseconds *after* the block screen is requested, which used to push it on top.
 */
public class AppLockAccessibilityService extends AccessibilityService {

    private static final String TAG = "SELF_PILOT_FREEZER";

    /** Foreground resolution interval while the screen is interactive. */
    private static final long FOREGROUND_POLL_INTERVAL_MS = 300L;
    /** Minimum delay between two expensive (DB backed) restriction checks for one package. */
    private static final long RESTRICTION_RECHECK_MS = 1500L;
    /** Ignore a duplicate block request for the same package fired inside this window. */
    private static final long BLOCK_COOLDOWN_MS = 700L;
    /** Delay between the block request and each reinforcement pass. */
    private static final long REINFORCE_STEP_MS = 260L;
    private static final int MAX_REINFORCE_ATTEMPTS = 3;
    /** Launcher package list is refreshed periodically in case it changed. */
    private static final long LAUNCHER_REFRESH_MS = 5 * 60 * 1000L;

    /** How often the in-memory frozen cache is reconciled against the database. */
    private static final long CACHE_RECONCILE_INTERVAL_MS = 20_000L;

    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> knownLaunchers = Collections.synchronizedSet(new HashSet<>());

    private FreezerOverlayController overlayController;
    private long lastCacheReconcileAt = 0L;

    private volatile boolean watchdogActive = false;

    private String lastForegroundPackage;
    private String lastBlockedPackage;
    private long lastBlockedAt = 0L;
    private long lastLauncherRefreshAt = 0L;

    private String lastRestrictionCheckedPackage;
    private long lastRestrictionCheckAt = 0L;

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!watchdogActive) return;
            try {
                if (isScreenInteractive()) {
                    evaluateForeground("watchdog");
                }
            } catch (Throwable t) {
                Log.w(TAG, "[WATCHDOG] tick failed", t);
            }
            if (watchdogActive) {
                watchdogHandler.postDelayed(this, FOREGROUND_POLL_INTERVAL_MS);
            }
        }
    };

    // ------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "[LIFECYCLE] AccessibilityService CONNECTED");

        FrozenAppsManager.init(this);
        refreshLauncherPackages();

        if (overlayController == null) {
            overlayController = new FreezerOverlayController(this);
        }

        // Keep the always-on guardian alive whenever enforcement is available again.
        GuardianService.start(this);

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.notificationTimeout = 0;
            setServiceInfo(info);
        }

        watchdogActive = true;
        watchdogHandler.removeCallbacks(watchdogRunnable);
        watchdogHandler.post(watchdogRunnable);
        Log.i(TAG, "[LIFECYCLE] Watchdog started (" + FOREGROUND_POLL_INTERVAL_MS + " ms)");
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "[LIFECYCLE] AccessibilityService INTERRUPTED");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        watchdogActive = false;
        watchdogHandler.removeCallbacks(watchdogRunnable);
        if (overlayController != null) overlayController.hide();
        Log.w(TAG, "[LIFECYCLE] AccessibilityService UNBOUND - watchdog stopped");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        watchdogActive = false;
        watchdogHandler.removeCallbacksAndMessages(null);
        if (overlayController != null) overlayController.hide();
        bgExecutor.shutdown();
        Log.w(TAG, "[LIFECYCLE] AccessibilityService DESTROYED");
    }

    // ------------------------------------------------------------------------------------
    // Event path
    // ------------------------------------------------------------------------------------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }

        FrozenAppsManager.ensureInitialized(this);
        reconcileCacheIfDue();

        // Fast path: the event itself names a frozen package.
        CharSequence pkgSeq = event.getPackageName();
        if (pkgSeq != null && pkgSeq.length() > 0) {
            String eventPackage = pkgSeq.toString();
            if (!isProtectedPackage(eventPackage)
                    && FrozenAppsManager.isFrozen(this, eventPackage)) {
                Log.d(TAG, "[EVENT] Frozen package from event: " + eventPackage);
                enforceFreeze(eventPackage);
                return;
            }
        }

        // Authoritative path: inspect the actual window stack. Covers TYPE_WINDOWS_CHANGED,
        // null event packages and launcher / recents driven task resumption.
        evaluateForeground("event");
    }

    // ------------------------------------------------------------------------------------
    // Foreground resolution + enforcement
    // ------------------------------------------------------------------------------------

    /**
     * Resolves the package the user is actually looking at, then applies freezer rules.
     */
    private void evaluateForeground(String source) {
        String foreground = resolveForegroundPackage();
        if (foreground == null) return;

        if (!foreground.equals(lastForegroundPackage)) {
            Log.d(TAG, "[" + source + "] foreground -> " + foreground);
            lastForegroundPackage = foreground;
        }

        // 1. Freezer: unconditional and checked before every other rule, so no whitelist
        //    entry can ever exempt a frozen app.
        if (FrozenAppsManager.isFrozen(this, foreground)) {
            enforceFreeze(foreground);
            return;
        }

        // 2. The user left the blocked app: drop the block surface immediately.
        dismissStaleOverlay(foreground);

        if (isProtectedPackage(foreground)) return;

        // 3. Non-freeze restrictions (focus mode / exam mode / limits / schedule).
        maybeCheckRestrictions(foreground);
    }

    /**
     * Blocks a frozen package with the overlay surface, which cannot be bypassed by a
     * background activity launch refusal, an activity race or a recents re-launch.
     * Falls back to the activity screen only if the overlay could not be added.
     */
    private void enforceFreeze(String packageName) {
        if (!isBlockable(packageName)) return;

        if (overlayController == null) {
            overlayController = new FreezerOverlayController(this);
        }

        boolean alreadyCovered = overlayController.isShowing()
                && packageName.equals(overlayController.getTargetPackage());

        if (!alreadyCovered) {
            Log.i(TAG, "[ENFORCE] Frozen app blocked -> " + packageName);
        }

        overlayController.show(packageName);

        if (!overlayController.isShowing()) {
            // Overlay window could not be attached (rare OEM restriction) - fall back.
            Log.w(TAG, "[ENFORCE] Overlay unavailable, using activity fallback");
            enforceBlock(packageName);
        }
    }

    private void dismissStaleOverlay(String foreground) {
        if (overlayController == null || !overlayController.isShowing()) return;
        String target = overlayController.getTargetPackage();
        if (target != null && !target.equals(foreground)) {
            Log.d(TAG, "[OVERLAY] Foreground moved to " + foreground + " -> hiding block surface");
            overlayController.hide();
        }
    }

    /**
     * The in-memory frozen set is what makes blocking instant. If it ever drifts from the
     * database (reload failure, process reuse), blocking would silently stop working until
     * the app is opened again. This keeps both in sync without user interaction.
     */
    private void reconcileCacheIfDue() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCacheReconcileAt < CACHE_RECONCILE_INTERVAL_MS) return;
        lastCacheReconcileAt = now;
        FrozenAppsManager.reconcile(this);
    }

    /**
     * Resolves the foreground package from the window stack.
     *
     * The active window (the one receiving input) is authoritative. The focused window and
     * the active-window root node are used as fallbacks so that a transient starting window
     * (splash) or an OEM layout can never hide a frozen app.
     */
    private String resolveForegroundPackage() {
        String active = null;
        String focused = null;
        String firstApplication = null;

        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) continue;
                    if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;

                    String pkg = windowPackage(window);
                    if (pkg == null) continue;
                    // Our own windows (block activity / overlay host) never count as the
                    // "app the user is using"; the app underneath is what matters.
                    if (pkg.equals(getPackageName())) continue;

                    if (firstApplication == null) {
                        firstApplication = pkg;
                    }
                    if (window.isActive()) {
                        active = pkg;
                        break;
                    }
                    if (focused == null && window.isFocused()) {
                        focused = pkg;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Some devices throw while a transition is in flight; fall through to the root node.
        }

        if (active != null) return active;
        if (focused != null) return focused;

        String rootPackage = activeRootPackage();
        if (rootPackage != null && !rootPackage.equals(getPackageName())) return rootPackage;

        return firstApplication;
    }

    private String windowPackage(AccessibilityWindowInfo window) {
        try {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) return null;
            CharSequence pkg = root.getPackageName();
            if (pkg == null || pkg.length() == 0) return null;
            return pkg.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String activeRootPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return null;
            CharSequence pkg = root.getPackageName();
            if (pkg == null || pkg.length() == 0) return null;
            return pkg.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void maybeCheckRestrictions(String packageName) {
        long now = SystemClock.elapsedRealtime();
        if (packageName.equals(lastRestrictionCheckedPackage)
                && now - lastRestrictionCheckAt < RESTRICTION_RECHECK_MS) {
            return;
        }
        lastRestrictionCheckedPackage = packageName;
        lastRestrictionCheckAt = now;

        bgExecutor.execute(() -> {
            try {
                if (RestrictionChecker.isAppRestricted(getApplicationContext(), packageName)) {
                    enforceBlock(packageName);
                }
            } catch (Throwable t) {
                Log.w(TAG, "[RESTRICTION] check failed for " + packageName, t);
            }
        });
    }

    /**
     * Shows the block screen and verifies that it actually ended up on top.
     *
     * The frozen activity often finishes its resume *after* the block screen is requested,
     * stealing the top spot. Each reinforcement pass detects that and re-asserts the block,
     * so a launch attempt can never win by retrying.
     */
    private void enforceBlock(final String packageName) {
        if (!isBlockable(packageName)) return;

        long now = SystemClock.elapsedRealtime();
        if (packageName.equals(lastBlockedPackage) && now - lastBlockedAt < BLOCK_COOLDOWN_MS) {
            return;
        }
        lastBlockedPackage = packageName;
        lastBlockedAt = now;

        Log.i(TAG, "[ENFORCE] Blocking frozen/restricted app -> " + packageName);
        launchBlockScreen(packageName);

        for (int i = 1; i <= MAX_REINFORCE_ATTEMPTS; i++) {
            final int attempt = i;
            watchdogHandler.postDelayed(() -> reinforceBlock(packageName, attempt),
                    REINFORCE_STEP_MS * i);
        }
    }

    private void reinforceBlock(String packageName, int attempt) {
        if (!isScreenInteractive()) return;

        String top = resolveForegroundPackage();
        if (top == null || !top.equals(packageName)) return;

        Log.w(TAG, "[ENFORCE] App resurfaced on top, reinforcing block (pass "
                + attempt + "/" + MAX_REINFORCE_ATTEMPTS + ") -> " + packageName);

        // Move the offending task off the screen first, then re-assert the block screen.
        performGlobalAction(GLOBAL_ACTION_HOME);
        watchdogHandler.postDelayed(() -> launchBlockScreen(packageName), 140L);
    }

    private void launchBlockScreen(String packageName) {
        if (!isBlockable(packageName)) return;
        try {
            Intent intent = new Intent(this, BlockActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.putExtra(BlockActivity.EXTRA_PACKAGE_NAME, packageName);
            startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "[ENFORCE] Failed to launch BlockActivity for " + packageName, t);
        }
    }

    private boolean isBlockable(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        return !isProtectedPackage(packageName);
    }

    private boolean isScreenInteractive() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable ignored) {
            return true;
        }
    }

    // ------------------------------------------------------------------------------------
    // Package classification
    // ------------------------------------------------------------------------------------

    /**
     * Packages that must never be blocked, otherwise the user could lose access to the
     * launcher, the system UI, SelfPilot itself or the ability to answer an incoming call.
     * Note: a frozen user app is *not* protected by any of these rules.
     */
    private boolean isProtectedPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return true;
        if (packageName.equals(getPackageName())) return true;
        if ("android".equals(packageName) || "com.android.systemui".equals(packageName)) return true;
        if (knownLaunchers.contains(packageName)) return true;
        if ("com.android.settings".equals(packageName) || packageName.endsWith(".settings")) return true;
        if (isTelephonyUi(packageName)) return true;
        return false;
    }

    /**
     * In-call / dialer UI stays reachable even if the user froze a dialer package,
     * so an incoming call can always be answered.
     */
    private boolean isTelephonyUi(String packageName) {
        if ("com.android.phone".equals(packageName)
                || "com.android.server.telecom".equals(packageName)
                || "com.google.android.dialer".equals(packageName)) {
            return true;
        }
        return packageName.contains("incall") || packageName.contains("dialer");
    }

    /**
     * Queries PackageManager for every package handling CATEGORY_HOME.
     */
    private void refreshLauncherPackages() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLauncherRefreshAt < 1000L && !knownLaunchers.isEmpty()) return;
        lastLauncherRefreshAt = now;

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
            Log.d(TAG, "[LAUNCHERS] Home packages: " + knownLaunchers);
        } catch (Throwable t) {
            Log.e(TAG, "[LAUNCHERS] Error querying home launchers", t);
        }
    }
}
