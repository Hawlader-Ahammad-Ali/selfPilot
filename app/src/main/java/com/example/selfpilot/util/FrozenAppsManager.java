package com.example.selfpilot.util;

import android.content.Context;
import android.util.Log;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.AppInfoEntity;
import com.example.selfpilot.data.FrozenAppEntity;
import com.example.selfpilot.data.RestrictionRuleEntity;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single source of truth for frozen app packages across the entire application.
 * Maintains an in-memory thread-safe set for instantaneous (0ms) lookup on the
 * hot path of accessibility events, and synchronously/asynchronously manages Room DB persistence.
 */
public class FrozenAppsManager {

    private static final String TAG = "SELF_PILOT_FREEZER";
    private static final Set<String> frozenPackageNames = ConcurrentHashMap.newKeySet();
    private static volatile boolean isInitialized = false;
    private static volatile boolean loadInFlight = false;
    private static volatile long lastLoadFailureAt = 0L;
    private static final long LOAD_RETRY_BACKOFF_MS = 15_000L;
    private static final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    /**
     * Initializes the in-memory frozen package set from Room DB.
     * Automatically purges SelfPilot package from frozen state to guarantee it is never frozen.
     */
    public static void init(Context context) {
        if (context == null) return;
        if (loadInFlight) return;
        loadInFlight = true;

        Context appContext = context.getApplicationContext();
        String selfPkg = appContext.getPackageName();
        isInitialized = false;

        // 1. Purge SelfPilot from memory cache immediately
        frozenPackageNames.remove("com.example.selfpilot");
        frozenPackageNames.remove(selfPkg);

        // 2. Load from DB in background and purge SelfPilot if previously persisted
        dbExecutor.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getDatabase(appContext);

                // Purge SelfPilot from frozen table
                db.frozenAppDao().unfreezeApp("com.example.selfpilot", System.currentTimeMillis());
                db.frozenAppDao().unfreezeApp(selfPkg, System.currentTimeMillis());

                // Reset isFrozen and isBlocked in app_info for SelfPilot
                AppInfoEntity selfApp = db.appInfoDao().getAppInfo(selfPkg);
                if (selfApp != null && (selfApp.isFrozen || selfApp.isBlocked)) {
                    selfApp.isFrozen = false;
                    selfApp.isBlocked = false;
                    db.appInfoDao().update(selfApp);
                }

                // Reset restriction rule for SelfPilot
                RestrictionRuleEntity selfRule = db.restrictionRuleDao().getRuleByPackageName(selfPkg);
                if (selfRule != null && selfRule.isBlocked) {
                    selfRule.isBlocked = false;
                    db.restrictionRuleDao().update(selfRule);
                }

                List<FrozenAppEntity> list = db.frozenAppDao().getFrozenApps();
                Set<String> newSet = new HashSet<>();
                if (list != null) {
                    for (FrozenAppEntity entity : list) {
                        if (entity.isFrozen && !entity.packageName.equals("com.example.selfpilot") && !entity.packageName.equals(selfPkg)) {
                            newSet.add(entity.packageName);
                        }
                    }
                }
                frozenPackageNames.clear();
                frozenPackageNames.addAll(newSet);
                Log.i(TAG, "[MANAGER] Frozen packages loaded (" + newSet.size() + "): " + newSet);
            } catch (Exception e) {
                Log.e(TAG, "[MANAGER] Failed to load frozen packages from DB", e);
                lastLoadFailureAt = System.currentTimeMillis();
            } finally {
                // Never leave the manager in a permanent "cold start" state: every
                // isFrozen() call would otherwise hit the database synchronously.
                if (lastLoadFailureAt == 0L) {
                    isInitialized = true;
                }
                loadInFlight = false;
            }
        });
    }

    /**
     * Cheap guard used on the hot path (accessibility events / watchdog ticks).
     * Only schedules a load when the cache was never seeded in this process.
     */
    public static void ensureInitialized(Context context) {
        if (context == null) return;

        if (isInitialized) {
            lastLoadFailureAt = 0L;
            return;
        }

        // A failed load is retried with a back-off instead of on every hot-path call,
        // otherwise a broken database would turn every watchdog tick into a DB query.
        long now = System.currentTimeMillis();
        if (lastLoadFailureAt != 0L && now - lastLoadFailureAt < LOAD_RETRY_BACKOFF_MS) {
            return;
        }
        init(context);
    }

    /**
     * Compares the in-memory cache with the database and triggers a reload when they drift.
     *
     * Without this, a failed or skipped reload leaves isInitialized=true with an empty set,
     * which silently disables all blocking until the app UI is opened again.
     */
    public static void reconcile(Context context) {
        if (context == null || loadInFlight) return;
        Context appContext = context.getApplicationContext();

        dbExecutor.execute(() -> {
            try {
                int dbCount = AppDatabase.getDatabase(appContext).frozenAppDao().getFrozenCount();
                if (!isInitialized || dbCount == frozenPackageNames.size()) return;

                Log.w(TAG, "[MANAGER] Cache drift (db=" + dbCount + ", memory="
                        + frozenPackageNames.size() + ") -> reloading");
                isInitialized = false;
                lastLoadFailureAt = 0L;
                init(appContext);
            } catch (Exception e) {
                Log.w(TAG, "[MANAGER] reconcile failed", e);
            }
        });
    }

    /**
     * Removes SelfPilot from the frozen set and from every related table.
     * Called by the guardian service so the user's own app can never be frozen,
     * no matter what ended up persisted by a previous version or an edge case.
     */
    public static void purgeSelf(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        String selfPkg = appContext.getPackageName();

        frozenPackageNames.remove(selfPkg);
        frozenPackageNames.remove("com.example.selfpilot");

        dbExecutor.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getDatabase(appContext);
                long now = System.currentTimeMillis();
                db.frozenAppDao().unfreezeApp(selfPkg, now);
                db.frozenAppDao().unfreezeApp("com.example.selfpilot", now);

                AppInfoEntity selfApp = db.appInfoDao().getAppInfo(selfPkg);
                if (selfApp != null && (selfApp.isFrozen || selfApp.isBlocked)) {
                    selfApp.isFrozen = false;
                    selfApp.isBlocked = false;
                    db.appInfoDao().update(selfApp);
                }

                RestrictionRuleEntity selfRule = db.restrictionRuleDao().getRuleByPackageName(selfPkg);
                if (selfRule != null && selfRule.isBlocked) {
                    selfRule.isBlocked = false;
                    db.restrictionRuleDao().update(selfRule);
                }
            } catch (Exception e) {
                Log.e(TAG, "[MANAGER] purgeSelf failed", e);
            }
        });
    }

    /**
     * Fast, thread-safe check whether a package is frozen.
     * Evaluates in-memory set immediately. If the manager hasn't initialized yet,
     * falls back to a fast direct DB query so no launch is ever missed during cold start.
     * Guaranteed to return false for SelfPilot itself.
     */
    public static boolean isFrozen(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        if ("com.example.selfpilot".equals(packageName)) return false;
        if (context != null && packageName.equals(context.getPackageName())) return false;

        // Hot-path in-memory check (0 milliseconds)
        if (frozenPackageNames.contains(packageName)) {
            return true;
        }

        // Cold-start fallback: if not yet initialized, check DB synchronously
        if (!isInitialized && context != null) {
            if (lastLoadFailureAt != 0L
                    && System.currentTimeMillis() - lastLoadFailureAt < LOAD_RETRY_BACKOFF_MS) {
                return false;
            }
            try {
                AppDatabase db = AppDatabase.getDatabase(context.getApplicationContext());
                FrozenAppEntity entity = db.frozenAppDao().getFrozenApp(packageName);
                if (entity != null && entity.isFrozen) {
                    frozenPackageNames.add(packageName);
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "[MANAGER] DB fallback check error", e);
            }
        }

        return false;
    }

    /**
     * Adds an app to the frozen set immediately in memory, and persists to DB.
     * Rejects freezing SelfPilot itself.
     */
    public static void freezeApp(Context context, String packageName, String appName, String mode) {
        if (packageName == null || packageName.isEmpty()) return;
        if ("com.example.selfpilot".equals(packageName)) return;
        if (context != null && packageName.equals(context.getPackageName())) return;
        Context appContext = context.getApplicationContext();

        // 1. Immediately update in-memory set (0ms)
        frozenPackageNames.add(packageName);
        Log.i(TAG, "[FREEZE] FrozenAppsManager: added to memory -> " + packageName);

        // 2. Persist to DB in background
        dbExecutor.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getDatabase(appContext);
                long now = System.currentTimeMillis();

                FrozenAppEntity frozen = new FrozenAppEntity(packageName, appName != null ? appName : packageName, true, now);
                frozen.restrictionMode = mode != null ? mode : "STANDARD";
                db.frozenAppDao().insert(frozen);

                AppInfoEntity appInfo = db.appInfoDao().getAppInfo(packageName);
                if (appInfo == null) {
                    appInfo = new AppInfoEntity(packageName, appName != null ? appName : packageName, false);
                }
                appInfo.isFrozen = true;
                appInfo.isBlocked = true;
                db.appInfoDao().update(appInfo);

                RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(packageName);
                if (rule == null) {
                    rule = new RestrictionRuleEntity();
                    rule.packageName = packageName;
                }
                rule.isBlocked = true;
                db.restrictionRuleDao().insert(rule);

                Log.i(TAG, "[FREEZE] FrozenAppsManager: persisted to DB -> " + packageName);
            } catch (Exception e) {
                Log.e(TAG, "[FREEZE] Error persisting freeze for " + packageName, e);
            } finally {
                // Re-assert in memory: a concurrent init()/reload must never lose a freeze.
                frozenPackageNames.add(packageName);
            }
        });
    }

    /**
     * Removes an app from the frozen set immediately in memory, and updates DB.
     */
    public static void unfreezeApp(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        Context appContext = context.getApplicationContext();

        // 1. Immediately remove from in-memory set (0ms)
        frozenPackageNames.remove(packageName);
        Log.i(TAG, "[UNFREEZE] FrozenAppsManager: removed from memory -> " + packageName);

        // 2. Update DB in background
        dbExecutor.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getDatabase(appContext);
                long now = System.currentTimeMillis();
                db.frozenAppDao().unfreezeApp(packageName, now);

                AppInfoEntity appInfo = db.appInfoDao().getAppInfo(packageName);
                if (appInfo != null) {
                    appInfo.isFrozen = false;
                    appInfo.isBlocked = false;
                    db.appInfoDao().update(appInfo);
                }

                RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(packageName);
                if (rule != null) {
                    rule.isBlocked = false;
                    db.restrictionRuleDao().update(rule);
                }

                Log.i(TAG, "[UNFREEZE] FrozenAppsManager: updated DB -> " + packageName);
            } catch (Exception e) {
                Log.e(TAG, "[UNFREEZE] Error unfreezing in DB for " + packageName, e);
            } finally {
                // A concurrent reload could have re-added it from a stale row.
                frozenPackageNames.remove(packageName);
            }
        });
    }

    public static Set<String> getFrozenPackages() {
        return Collections.unmodifiableSet(frozenPackageNames);
    }
}


