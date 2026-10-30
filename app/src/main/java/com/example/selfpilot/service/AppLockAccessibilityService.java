package com.example.selfpilot.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.FrozenAppEntity;
import com.example.selfpilot.ui.block.BlockActivity;
import com.example.selfpilot.util.RestrictionChecker;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Accessibility service that detects foreground app changes and enforces the App Freezer.
 *
 * Architecture notes (important for understanding bypass vectors):
 *  - We listen to TYPE_WINDOW_STATE_CHANGED *and* TYPE_WINDOWS_CHANGED so that all
 *    foreground transitions are captured, including Recent-Apps resume paths.
 *  - We maintain an in-memory snapshot of frozen package names (refreshed from DB
 *    each time a new app enters the foreground, and eagerly on service connect/disconnect).
 *  - We do NOT use a ConcurrentHashMap dedup guard that skips repeated events — every
 *    event for a frozen app must trigger a block attempt to prevent rapid-tap bypass.
 *  - We track the last package we attempted to block so we don't re-launch BlockActivity
 *    when BlockActivity itself fires events (which carries our own package name).
 *  - BlockActivity is launched with FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK
 *    to kill the frozen app's foreground task and bring our block screen to the front.
 */
public class AppLockAccessibilityService extends AccessibilityService {

    private static final String TAG = "SELF_PILOT_FREEZER";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    /**
     * In-memory snapshot of currently-frozen package names.
     * Refreshed from DB on every foreground change and on service lifecycle events.
     * Access is confined to the background executor thread; the set itself is replaced
     * atomically via a volatile reference so the main thread can read it without locking.
     */
    private volatile Set<String> frozenPackages = new HashSet<>();

    /**
     * Tracks whether a BlockActivity launch is already in progress for the current
     * foreground package, preventing duplicate launches for the same event burst.
     * Reset to null whenever the user navigates to the Home launcher or another safe package.
     */
    private volatile String blockingInProgressForPackage = null;

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "[LIFECYCLE] AccessibilityService CONNECTED");

        // Dynamically subscribe to both event types so Recent-App resumptions are caught.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
            setServiceInfo(info);
            Log.i(TAG, "[LIFECYCLE] Event subscription updated: TYPE_WINDOW_STATE_CHANGED | TYPE_WINDOWS_CHANGED");
        } else {
            Log.w(TAG, "[LIFECYCLE] getServiceInfo() returned null — cannot update event types at runtime");
        }

        refreshFrozenPackagesAsync();
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

    // -------------------------------------------------------------------------
    // Event handling
    // -------------------------------------------------------------------------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int eventType = event.getEventType();
        CharSequence pkgSeq = event.getPackageName();
        String eventPackage = pkgSeq != null ? pkgSeq.toString() : null;

        // Log every relevant event for Phase 1 instrumentation
        Log.d(TAG, "[EVENT] type=" + AccessibilityEvent.eventTypeToString(eventType)
                + " pkg=" + eventPackage
                + " class=" + event.getClassName());

        // We only act when a foreground window appears or changes
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }

        if (eventPackage == null || eventPackage.isEmpty()) {
            Log.d(TAG, "[EVENT] Skipping — null/empty package");
            return;
        }

        // Skip our own package and known safe system packages immediately
        // But first: if we're transitioning to a safe package (Home, launcher, etc.),
        // reset the blockingInProgressForPackage so the next frozen-app launch re-blocks.
        if (isSafePackage(eventPackage)) {
            if (blockingInProgressForPackage != null) {
                Log.d(TAG, "[EVENT] Transitioning to safe package " + eventPackage
                        + " — resetting blockingInProgress (was: " + blockingInProgressForPackage + ")");
                blockingInProgressForPackage = null;
            }
            return;
        }

        Log.i(TAG, "[EVENT] Foreground change → pkg=" + eventPackage
                + " frozenSnapshot=" + frozenPackages
                + " blockingInProgress=" + blockingInProgressForPackage);

        // Refresh the frozen-package snapshot in background for the next decision
        // (non-blocking; we use the current snapshot for this decision)
        refreshFrozenPackagesAsync();

        // Fast-path check using the in-memory snapshot (no DB hit on hot path)
        if (frozenPackages.contains(eventPackage)) {
            Log.i(TAG, "[DECISION] pkg=" + eventPackage + " IS in frozen snapshot → enforcing block");
            enforceBlock(eventPackage);
        } else {
            Log.d(TAG, "[DECISION] pkg=" + eventPackage + " NOT in frozen snapshot → no block");
            // Clear in-progress marker if we've navigated to a genuinely different app
            if (!eventPackage.equals(blockingInProgressForPackage)) {
                blockingInProgressForPackage = null;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Enforcement
    // -------------------------------------------------------------------------

    /**
     * Enforces the block for a frozen package.
     *
     * KEY DESIGN:
     *  - blockingInProgressForPackage prevents re-launching BlockActivity if it's
     *    already in the foreground for this exact package (avoids infinite loop when
     *    BlockActivity fires its own TYPE_WINDOW_STATE_CHANGED event).
     *  - It is cleared whenever the user navigates to the Home screen or any safe package,
     *    ensuring the next launch of the frozen app is always re-blocked.
     *  - The Activity's singleTask launch mode handles concurrent block requests for the
     *    same package via onNewIntent.
     */
    private void enforceBlock(String packageName) {
        // Only skip if BlockActivity is already the foreground activity for this exact package.
        // This guard is reset when the user goes to Home (safe package transition above).
        if (packageName.equals(blockingInProgressForPackage)) {
            Log.d(TAG, "[ENFORCE] BlockActivity already visible for " + packageName + " — skipping duplicate launch");
            return;
        }

        Log.i(TAG, "[ENFORCE] Initiating block for pkg=" + packageName);
        blockingInProgressForPackage = packageName;

        bgExecutor.execute(() -> {
            // Re-verify from DB in case the user unfroze the app between the event and now
            boolean confirmedFrozen = RestrictionChecker.isAppFrozenOnly(
                    getApplicationContext(), packageName);

            Log.i(TAG, "[ENFORCE][BG] DB re-verification for pkg=" + packageName
                    + " confirmedFrozen=" + confirmedFrozen);

            if (!confirmedFrozen) {
                // The snapshot was stale — app was unfrozen between snapshot refresh and this check
                Log.w(TAG, "[ENFORCE][BG] Stale snapshot — " + packageName + " is no longer frozen. Skipping block.");
                mainHandler.post(() -> blockingInProgressForPackage = null);
                return;
            }

            mainHandler.post(() -> {
                Log.i(TAG, "[ENFORCE][MAIN] Launching BlockActivity for pkg=" + packageName);
                try {
                    Intent intent = new Intent(AppLockAccessibilityService.this, BlockActivity.class);
                    // FLAG_ACTIVITY_NEW_TASK: required when starting from a non-Activity context
                    // FLAG_ACTIVITY_CLEAR_TASK: terminates the frozen app's task stack,
                    //   preventing the user from pressing Back to return to it
                    // Do NOT use FLAG_ACTIVITY_CLEAR_TOP here — that only affects our own task
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    intent.putExtra(BlockActivity.EXTRA_PACKAGE_NAME, packageName);
                    startActivity(intent);
                    Log.i(TAG, "[ENFORCE][MAIN] BlockActivity launch succeeded for pkg=" + packageName);
                } catch (Exception e) {
                    Log.e(TAG, "[ENFORCE][MAIN] BlockActivity launch FAILED for pkg=" + packageName, e);
                    blockingInProgressForPackage = null;
                }
            });
        });
    }

    // -------------------------------------------------------------------------
    // Frozen-package snapshot management
    // -------------------------------------------------------------------------

    /**
     * Refreshes the in-memory frozen-package snapshot from the database.
     * Called asynchronously so it never blocks event processing.
     */
    private void refreshFrozenPackagesAsync() {
        bgExecutor.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getDatabase(getApplicationContext());
                List<FrozenAppEntity> frozenList = db.frozenAppDao().getFrozenApps();
                Set<String> newSnapshot = new HashSet<>();
                if (frozenList != null) {
                    for (FrozenAppEntity entity : frozenList) {
                        if (entity.isFrozen) {
                            newSnapshot.add(entity.packageName);
                        }
                    }
                }
                frozenPackages = newSnapshot; // atomic volatile write
                Log.d(TAG, "[SNAPSHOT] Frozen packages refreshed: " + newSnapshot);
            } catch (Exception e) {
                Log.e(TAG, "[SNAPSHOT] Failed to refresh frozen packages from DB", e);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true for packages that should never trigger a block check.
     * This includes SelfPilot itself, Android system UI, launchers, and dialers.
     */
    private boolean isSafePackage(String packageName) {
        if (packageName == null) return true;
        String own = getPackageName();
        return packageName.equals(own)
                || packageName.equals("android")
                || packageName.equals("com.android.systemui")
                || packageName.equals("com.android.inputmethod.latin")
                || packageName.contains("launcher")
                || packageName.contains("dialer")
                || packageName.contains("incall")
                || packageName.contains("settings");
    }
}
