package com.example.selfpilot.util;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class UsageUtil {

    public static boolean hasUsageStatsPermission(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) {
            return false;
        }
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName());
        
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Per-package usage for the given window.
     *
     * queryUsageStats() returns one bucket per package per calendar interval. The previous code
     * merged those buckets with UsageStats.add(), which sums totalTimeInForeground - so a
     * window that touches two buckets counted the same minutes twice and the reported screen
     * time drifted far above the device's own figure. queryAndAggregateUsageStats() lets the
     * system aggregate, so every package is counted once.
     */
    public static Map<String, UsageStats> getUsageStats(Context context, long startTime, long endTime) {
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return null;

        try {
            Map<String, UsageStats> aggregated = usm.queryAndAggregateUsageStats(startTime, endTime);
            if (aggregated != null) {
                // Never hand out buckets that ended before the window started.
                for (java.util.Iterator<Map.Entry<String, UsageStats>> it =
                     aggregated.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<String, UsageStats> entry = it.next();
                    UsageStats stats = entry.getValue();
                    if (stats == null) {
                        it.remove();
                        continue;
                    }
                    if (stats.getLastTimeUsed() < startTime
                            && stats.getTotalTimeInForeground() <= 0) {
                        it.remove();
                    }
                }
                return aggregated;
            }
        } catch (Throwable ignored) {
        }

        // Fallback for ROMs that fail the aggregate call: keep the system buckets as-is
        // instead of adding them together.
        try {
            List<UsageStats> buckets =
                    usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime);
            if (buckets == null || buckets.isEmpty()) return null;

            Map<String, UsageStats> byPackage = new java.util.HashMap<>();
            for (UsageStats stats : buckets) {
                if (stats == null) continue;
                UsageStats existing = byPackage.get(stats.getPackageName());
                // Keep the bucket that actually overlaps the requested window.
                if (existing == null || stats.getLastTimeUsed() > existing.getLastTimeUsed()) {
                    byPackage.put(stats.getPackageName(), stats);
                }
            }
            return byPackage;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Time some app was actually in the foreground - the definition the phone's own
     * "Screen time" screen uses.
     *
     * A straight sum of per-app foreground time overlaps (split screen, picture-in-picture),
     * and plain screen-on time also counts the lock screen and always-on display, which is why
     * SelfPilot used to report ~30 minutes more than the device. Here the window is rebuilt as
     * one timeline: an app resuming opens a segment, the same app pausing closes it. Segments
     * therefore never overlap and never cover the lock screen.
     */
    public static long getForegroundMillis(Context context, long start, long end) {
        if (context == null || end <= start) return 0L;
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return 0L;

        final int EVENT_RESUME = UsageEvents.Event.MOVE_TO_FOREGROUND;   // 1
        final int EVENT_PAUSE = UsageEvents.Event.MOVE_TO_BACKGROUND;    // 2

        long total = 0L;
        long segmentStart = -1L;
        String owner = null;

        try {
            UsageEvents events = usm.queryEvents(start, end);
            UsageEvents.Event event = new UsageEvents.Event();
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();
                long ts = Math.min(Math.max(event.getTimeStamp(), start), end);
                String pkg = event.getPackageName();

                if (type == EVENT_RESUME) {
                    if (segmentStart >= 0) {
                        // Hand over from the previous owner without a gap.
                        total += Math.max(0L, ts - segmentStart);
                    }
                    segmentStart = ts;
                    owner = pkg;
                } else if (type == EVENT_PAUSE || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                    if (segmentStart >= 0 && pkg != null && pkg.equals(owner)) {
                        total += Math.max(0L, ts - segmentStart);
                        segmentStart = -1L;
                        owner = null;
                    }
                } else if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE
                        || type == UsageEvents.Event.KEYGUARD_SHOWN) {
                    if (segmentStart >= 0) {
                        total += Math.max(0L, ts - segmentStart);
                        segmentStart = -1L;
                        owner = null;
                    }
                }
            }
        } catch (Throwable ignored) {
            return Math.max(0L, total);
        }

        if (segmentStart >= 0) total += Math.max(0L, end - segmentStart);
        return Math.min(total, Math.max(0L, end - start));
    }

    /**
     * Foreground minutes split into the 24 hours of the day starting at {@code start}.
     *
     * Uses the same resume/pause timeline as {@link #getForegroundMillis}, so the bars always
     * add up to the headline number on the Home screen.
     */
    public static long[] getHourlyUsage(Context context, long start, long end) {
        long[] buckets = new long[24];
        if (context == null || end <= start) return buckets;

        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return buckets;

        long segmentStart = -1L;
        String owner = null;

        try {
            UsageEvents events = usm.queryEvents(start, end);
            UsageEvents.Event event = new UsageEvents.Event();
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();
                long ts = Math.min(Math.max(event.getTimeStamp(), start), end);
                String pkg = event.getPackageName();

                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    if (segmentStart >= 0) {
                        addSegment(buckets, start, segmentStart, ts);
                    }
                    segmentStart = ts;
                    owner = pkg;
                } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND
                        || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                    if (segmentStart >= 0 && pkg != null && pkg.equals(owner)) {
                        addSegment(buckets, start, segmentStart, ts);
                        segmentStart = -1L;
                        owner = null;
                    }
                } else if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE
                        || type == UsageEvents.Event.KEYGUARD_SHOWN) {
                    if (segmentStart >= 0) {
                        addSegment(buckets, start, segmentStart, ts);
                        segmentStart = -1L;
                        owner = null;
                    }
                }
            }
        } catch (Throwable ignored) {
            return buckets;
        }

        if (segmentStart >= 0) addSegment(buckets, start, segmentStart, end);
        return buckets;
    }

    /** Splits one foreground segment across the hour buckets it touches. */
    private static void addSegment(long[] buckets, long dayStart, long from, long to) {
        long cursor = Math.max(from, dayStart);
        long limit = to;
        int guard = 0;
        while (cursor < limit && guard++ < 48) {
            int hour = (int) ((cursor - dayStart) / 3600000L);
            if (hour < 0) break;
            if (hour > 23) break;
            long hourEnd = dayStart + (hour + 1) * 3600000L;
            long partEnd = Math.min(limit, hourEnd);
            if (partEnd > cursor) buckets[hour] += partEnd - cursor;
            cursor = partEnd > cursor ? partEnd : cursor + 1;
        }
    }

    /**
     * Real screen-on time for the window, measured from the device's own
     * SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE events (and keyguard events).
     *
     * This is the number that should match what the phone reports: summing per-app foreground
     * time double counts overlapping windows (split screen, picture-in-picture) and includes
     * the launcher and system UI, which is why the total used to drift upwards.
     *
     * The state at {@code start} is recovered from a bounded look-back, so a phone that was
     * already unlocked at midnight is not under-counted and a phone that was off is not
     * over-counted.
     */
    public static long getScreenOnMillis(Context context, long start, long end) {
        if (context == null || end <= start) return 0L;
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return 0L;

        long total = 0L;
        long onSince = -1L;

        // 1. Was the screen already interactive when the window opened?
        try {
            long probeFrom = start - 2 * 60 * 60 * 1000L;
            if (probeFrom < 0) probeFrom = 0;
            UsageEvents probe = usm.queryEvents(probeFrom, start);
            UsageEvents.Event event = new UsageEvents.Event();
            boolean interactive = false;
            while (probe != null && probe.hasNextEvent()) {
                probe.getNextEvent(event);
                int type = event.getEventType();
                if (type == UsageEvents.Event.SCREEN_INTERACTIVE
                        || type == UsageEvents.Event.KEYGUARD_HIDDEN) {
                    interactive = true;
                } else if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE
                        || type == UsageEvents.Event.KEYGUARD_SHOWN) {
                    interactive = false;
                }
            }
            if (interactive) onSince = start;
        } catch (Throwable ignored) {
        }

        // 2. Walk the window's events and close each on/off segment.
        try {
            UsageEvents events = usm.queryEvents(start, end);
            UsageEvents.Event event = new UsageEvents.Event();
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();
                long ts = Math.min(event.getTimeStamp(), end);

                if (type == UsageEvents.Event.SCREEN_INTERACTIVE
                        || type == UsageEvents.Event.KEYGUARD_HIDDEN) {
                    if (onSince < 0) onSince = Math.max(ts, start);
                } else if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE
                        || type == UsageEvents.Event.KEYGUARD_SHOWN) {
                    if (onSince >= 0) {
                        total += Math.max(0L, ts - onSince);
                        onSince = -1L;
                    }
                }
            }
        } catch (Throwable ignored) {
            return Math.max(0L, total);
        }

        if (onSince >= 0) total += Math.max(0L, end - onSince);
        return Math.min(total, Math.max(0L, end - start));
    }

    /**
     * Every launchable user app, merged from two sources.
     *
     * On several OEM ROMs getInstalledApplications() comes back empty or partial even with
     * QUERY_ALL_PACKAGES granted, so the launcher query is used as a supplement - that is also
     * the list the user actually recognises. Each entry is guarded individually: one package
     * with broken resources must never abort the whole list.
     */
    public static List<ApplicationInfo> getInstalledApps(Context context) {
        if (context == null) return new ArrayList<>();
        PackageManager pm = context.getPackageManager();

        Map<String, ApplicationInfo> byPackage = new LinkedHashMap<>();

        try {
            List<ApplicationInfo> all = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            if (all != null) {
                for (ApplicationInfo info : all) {
                    try {
                        if (info == null || info.packageName == null) continue;
                        if (isSystem(info)) continue;
                        byPackage.put(info.packageName, info);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            // Fall through to the launcher query below.
        }

        try {
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolvers = pm.queryIntentActivities(launcher, 0);
            if (resolvers != null) {
                for (ResolveInfo resolver : resolvers) {
                    try {
                        if (resolver == null || resolver.activityInfo == null) continue;
                        ApplicationInfo info = resolver.activityInfo.applicationInfo;
                        if (info == null || info.packageName == null) continue;
                        if (isSystem(info)) continue;
                        if (!byPackage.containsKey(info.packageName)) {
                            byPackage.put(info.packageName, info);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Third source: the launcher service. A few OEM ROMs narrow package visibility even
        // for queryIntentActivities, but still hand the launcher list to this API.
        try {
            LauncherApps launcherApps =
                    (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (launcherApps != null) {
                List<LauncherActivityInfo> activities =
                        launcherApps.getActivityList(null, Process.myUserHandle());
                if (activities != null) {
                    for (LauncherActivityInfo activity : activities) {
                        try {
                            ApplicationInfo info = activity.getApplicationInfo();
                            if (info == null || info.packageName == null) continue;
                            if (isSystem(info)) continue;
                            if (!byPackage.containsKey(info.packageName)) {
                                byPackage.put(info.packageName, info);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return new ArrayList<>(byPackage.values());
    }

    private static boolean isSystem(ApplicationInfo info) {
        return (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                || (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    /**
     * PackageManager.loadLabel() throws Resources.NotFoundException for a handful of OEM /
     * sideloaded packages. That single failure used to kill the whole app-list loader, which is
     * why the list looked permanently empty on those phones.
     */
    public static String safeLabel(PackageManager pm, ApplicationInfo info) {
        if (info == null) return "";
        try {
            CharSequence label = info.loadLabel(pm);
            if (label != null && label.length() > 0) return label.toString().trim();
        } catch (Throwable ignored) {
        }
        try {
            String pkg = info.packageName;
            return pkg == null ? "" : pkg;
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static Drawable safeIcon(PackageManager pm, String packageName) {
        try {
            return pm.getApplicationIcon(packageName);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
