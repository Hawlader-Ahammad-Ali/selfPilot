package com.example.selfpilot.account;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.selfpilot.data.AchievementEntity;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.AppInfoEntity;
import com.example.selfpilot.data.ExamProfileEntity;
import com.example.selfpilot.data.FocusSessionEntity;
import com.example.selfpilot.data.FrozenAppEntity;
import com.example.selfpilot.data.GoalEntity;
import com.example.selfpilot.data.HonorScoreEntity;
import com.example.selfpilot.data.RestrictionRuleEntity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Serialises the portable part of the local database.
 *
 * WHAT IS BACKED UP
 *   scores, focus sessions, restriction rules, schedule, frozen list, exam profiles,
 *   app categories, goals, achievements and a small whitelist of user preferences.
 *
 * WHAT IS DELIBERATELY NOT BACKED UP
 *   Anything that describes one specific phone or one specific installation:
 *   AccessibilityService state, notification-listener state, usage-access permission,
 *   battery-optimisation exemption, exact-alarm permission, notification permission,
 *   the "onboarding completed" flag and the per-session emergency unlock grants.
 *
 * That is why a restored account can never make a new device look like its permissions are
 * already granted: the app has no code path that can apply a permission from the cloud, and
 * the payload does not even contain the values.
 */
public final class BackupCodec {

    public static final int VERSION = 1;

    /** Roughly two years of daily scores, and the last 500 focus sessions. */
    private static final int MAX_SCORES = 800;
    private static final int MAX_SESSIONS = 500;

    /**
     * Only these preferences travel between devices. Every other key in SelfPilotPrefs is
     * device-specific (permissions, onboarding, temporary grants) and is skipped.
     */
    private static final String[] PORTABLE_PREFS = {
            "daily_limit_minutes",
            "exam_allow_calls",
            "exam_allow_messages"
    };

    private BackupCodec() {
    }

    // ------------------------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------------------------

    public static JSONObject export(Context context) throws Exception {
        AppDatabase db = AppDatabase.getDatabase(context);
        JSONObject root = new JSONObject();
        root.put("v", VERSION);
        root.put("exportedAt", System.currentTimeMillis());

        JSONArray scores = new JSONArray();
        List<HonorScoreEntity> scoreList = db.honorScoreDao().getScoresLimited(MAX_SCORES);
        if (scoreList != null) {
            for (HonorScoreEntity s : scoreList) {
                JSONObject o = new JSONObject();
                o.put("date", s.getDate());
                o.put("studyPoints", s.getStudyPoints());
                o.put("disciplineScore", s.getDisciplineScore());
                o.put("streak", s.getStreak());
                o.put("honorLevel", s.getHonorLevel());
                scores.put(o);
            }
        }
        root.put("scores", scores);

        JSONArray sessions = new JSONArray();
        List<FocusSessionEntity> sessionList = db.focusSessionDao().getSessionsLimited(MAX_SESSIONS);
        if (sessionList != null) {
            for (FocusSessionEntity s : sessionList) {
                JSONObject o = new JSONObject();
                o.put("startTime", s.startTime);
                o.put("endTime", s.endTime);
                o.put("durationMinutes", s.durationMinutes);
                o.put("emergencyUnlocksUsed", s.emergencyUnlocksUsed);
                o.put("xpEarned", s.xpEarned);
                o.put("status", s.status == null ? "COMPLETED" : s.status);
                sessions.put(o);
            }
        }
        root.put("focusSessions", sessions);

        JSONArray rules = new JSONArray();
        List<RestrictionRuleEntity> ruleList = db.restrictionRuleDao().getAllRules();
        if (ruleList != null) {
            for (RestrictionRuleEntity r : ruleList) {
                JSONObject o = new JSONObject();
                o.put("packageName", r.packageName);
                o.put("limitMinutes", r.limitMinutes);
                o.put("isBlocked", r.isBlocked);
                o.put("startTimeMinutes", r.startTimeMinutes);
                o.put("endTimeMinutes", r.endTimeMinutes);
                o.put("daysOfWeek", r.daysOfWeek);
                o.put("suppressNotifications", r.suppressNotifications);
                rules.put(o);
            }
        }
        root.put("rules", rules);

        JSONArray frozen = new JSONArray();
        List<FrozenAppEntity> frozenList = db.frozenAppDao().getFrozenApps();
        if (frozenList != null) {
            for (FrozenAppEntity f : frozenList) {
                JSONObject o = new JSONObject();
                o.put("packageName", f.packageName);
                o.put("appName", f.appName);
                o.put("isFrozen", f.isFrozen);
                o.put("frozenAt", f.frozenAt);
                o.put("restrictionMode", f.restrictionMode);
                frozen.put(o);
            }
        }
        root.put("frozen", frozen);

        JSONArray exams = new JSONArray();
        List<ExamProfileEntity> examList = db.examProfileDao().getAllExams();
        if (examList != null) {
            for (ExamProfileEntity e : examList) {
                JSONObject o = new JSONObject();
                o.put("examName", e.examName);
                o.put("startDate", e.startDate);
                o.put("endDate", e.endDate);
                o.put("dailyGoalMinutes", e.dailyGoalMinutes);
                o.put("restrictedAppsCsv", e.restrictedAppsCsv);
                o.put("allowedAppsCsv", e.allowedAppsCsv);
                o.put("status", e.status);
                o.put("isActive", e.isActive);
                o.put("emergencyUnlocksUsed", e.emergencyUnlocksUsed);
                o.put("xpEarned", e.xpEarned);
                exams.put(o);
            }
        }
        root.put("exams", exams);

        JSONArray apps = new JSONArray();
        List<AppInfoEntity> appList = db.appInfoDao().getAllApps();
        if (appList != null) {
            for (AppInfoEntity a : appList) {
                // Only apps the user actually classified are worth sending.
                if (!a.isStudyApp && (a.category == null || a.category.isEmpty())) continue;
                JSONObject o = new JSONObject();
                o.put("packageName", a.packageName);
                o.put("appName", a.appName);
                o.put("isStudyApp", a.isStudyApp);
                o.put("category", a.category);
                o.put("dailyLimitMinutes", a.dailyLimitMinutes);
                apps.put(o);
            }
        }
        root.put("apps", apps);

        JSONArray goals = new JSONArray();
        List<GoalEntity> goalList = db.goalDao().getRecentGoals();
        if (goalList != null) {
            for (GoalEntity g : goalList) {
                JSONObject o = new JSONObject();
                o.put("date", g.date);
                o.put("targetProductiveMins", g.targetProductiveMins);
                o.put("actualProductiveMins", g.actualProductiveMins);
                o.put("targetFocusSessions", g.targetFocusSessions);
                o.put("actualFocusSessions", g.actualFocusSessions);
                goals.put(o);
            }
        }
        root.put("goals", goals);

        JSONArray achievements = new JSONArray();
        List<AchievementEntity> achievementList = db.achievementDao().getAllAchievements();
        if (achievementList != null) {
            for (AchievementEntity a : achievementList) {
                JSONObject o = new JSONObject();
                o.put("key", a.achievementKey);
                o.put("title", a.title);
                o.put("description", a.description);
                o.put("isUnlocked", a.isUnlocked);
                o.put("unlockedAt", a.unlockedAt);
                achievements.put(o);
            }
        }
        root.put("achievements", achievements);

        JSONObject prefs = new JSONObject();
        SharedPreferences appPrefs = context.getSharedPreferences("SelfPilotPrefs", Context.MODE_PRIVATE);
        for (String key : PORTABLE_PREFS) {
            if (isDeviceSpecificKey(key)) continue;
            if (appPrefs.contains(key)) prefs.put(key, appPrefs.getAll().get(key));
        }
        root.put("prefs", prefs);

        return root;
    }

    // ------------------------------------------------------------------------------------
    // Import (merge only - never deletes local rows)
    // ------------------------------------------------------------------------------------

    public static void importInto(Context context, JSONObject root) throws Exception {
        AppDatabase db = AppDatabase.getDatabase(context);

        JSONArray scores = root.optJSONArray("scores");
        if (scores != null) {
            for (int i = 0; i < scores.length(); i++) {
                JSONObject o = scores.optJSONObject(i);
                if (o == null) continue;
                long date = o.optLong("date", 0L);
                if (date <= 0L || db.honorScoreDao().getScoreByDate(date) != null) continue;

                HonorScoreEntity score = new HonorScoreEntity();
                score.setDate(date);
                score.setStudyPoints(o.optInt("studyPoints", 0));
                score.setDisciplineScore(o.optInt("disciplineScore", 100));
                score.setStreak(o.optInt("streak", 0));
                score.setHonorLevel(o.optString("honorLevel", "Beginner"));
                db.honorScoreDao().insert(score);
            }
        }

        JSONArray sessions = root.optJSONArray("focusSessions");
        if (sessions != null) {
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject o = sessions.optJSONObject(i);
                if (o == null) continue;
                long start = o.optLong("startTime", 0L);
                if (start <= 0L || db.focusSessionDao().getSessionByStart(start) != null) continue;

                FocusSessionEntity session = new FocusSessionEntity();
                session.startTime = start;
                session.endTime = o.optLong("endTime", start);
                session.durationMinutes = o.optInt("durationMinutes", 0);
                session.emergencyUnlocksUsed = o.optInt("emergencyUnlocksUsed", 0);
                session.xpEarned = o.optInt("xpEarned", 0);
                String status = o.optString("status", "COMPLETED");
                // A stale ACTIVE row from another phone must never lock this device.
                session.status = "ACTIVE".equals(status) ? "COMPLETED" : status;
                db.focusSessionDao().insert(session);
            }
        }

        JSONArray rules = root.optJSONArray("rules");
        if (rules != null) {
            for (int i = 0; i < rules.length(); i++) {
                JSONObject o = rules.optJSONObject(i);
                if (o == null) continue;
                String pkg = o.optString("packageName", "");
                if (pkg.isEmpty()) continue;

                RestrictionRuleEntity existing = db.restrictionRuleDao().getRuleByPackageName(pkg);
                RestrictionRuleEntity rule = existing != null ? existing : new RestrictionRuleEntity();
                rule.packageName = pkg;
                rule.limitMinutes = o.optInt("limitMinutes", 0);
                rule.isBlocked = o.optBoolean("isBlocked", false);
                rule.startTimeMinutes = o.optInt("startTimeMinutes", -1);
                rule.endTimeMinutes = o.optInt("endTimeMinutes", -1);
                rule.daysOfWeek = o.optString("daysOfWeek", "1,2,3,4,5,6,7");
                rule.suppressNotifications = o.optBoolean("suppressNotifications", false);
                rule.unlockedUntil = 0L;

                if (existing != null) db.restrictionRuleDao().update(rule);
                else db.restrictionRuleDao().insert(rule);
            }
        }

        JSONArray frozen = root.optJSONArray("frozen");
        if (frozen != null) {
            for (int i = 0; i < frozen.length(); i++) {
                JSONObject o = frozen.optJSONObject(i);
                if (o == null) continue;
                String pkg = o.optString("packageName", "");
                if (pkg.isEmpty() || pkg.equals(context.getPackageName())) continue;

                FrozenAppEntity existing = db.frozenAppDao().getFrozenApp(pkg);
                if (existing != null) {
                    existing.isFrozen = o.optBoolean("isFrozen", true);
                    existing.frozenAt = o.optLong("frozenAt", System.currentTimeMillis());
                    db.frozenAppDao().update(existing);
                } else {
                    FrozenAppEntity entity = new FrozenAppEntity(pkg,
                            o.optString("appName", pkg),
                            o.optBoolean("isFrozen", true),
                            o.optLong("frozenAt", System.currentTimeMillis()));
                    entity.restrictionMode = o.optString("restrictionMode", "STRICT_LOCK");
                    db.frozenAppDao().insert(entity);
                }
            }
        }

        JSONArray exams = root.optJSONArray("exams");
        if (exams != null) {
            for (int i = 0; i < exams.length(); i++) {
                JSONObject o = exams.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("examName", "");
                long startDate = o.optLong("startDate", 0L);
                if (name.isEmpty() || startDate <= 0L) continue;

                boolean exists = false;
                List<ExamProfileEntity> current = db.examProfileDao().getAllExams();
                if (current != null) {
                    for (ExamProfileEntity e : current) {
                        if (name.equals(e.examName) && e.startDate == startDate) {
                            exists = true;
                            break;
                        }
                    }
                }
                if (exists) continue;

                long endDate = o.optLong("endDate", startDate);
                // Only re-activate sessions that are still running right now.
                boolean active = o.optBoolean("isActive", false)
                        && endDate > System.currentTimeMillis();

                ExamProfileEntity exam = new ExamProfileEntity(
                        name,
                        startDate,
                        endDate,
                        o.optInt("dailyGoalMinutes", 120),
                        o.optString("restrictedAppsCsv", ""),
                        o.optString("allowedAppsCsv", ""));
                exam.status = o.optString("status", "PENDING");
                exam.isActive = active;
                exam.emergencyUnlocksUsed = o.optInt("emergencyUnlocksUsed", 0);
                exam.xpEarned = o.optInt("xpEarned", 0);
                db.examProfileDao().insert(exam);
            }
        }

        JSONArray apps = root.optJSONArray("apps");
        if (apps != null) {
            for (int i = 0; i < apps.length(); i++) {
                JSONObject o = apps.optJSONObject(i);
                if (o == null) continue;
                String pkg = o.optString("packageName", "");
                if (pkg.isEmpty()) continue;

                AppInfoEntity existing = db.appInfoDao().getAppInfo(pkg);
                if (existing == null) {
                    AppInfoEntity created = new AppInfoEntity(pkg,
                            o.optString("appName", pkg), o.optBoolean("isStudyApp", false));
                    created.category = o.optString("category", null);
                    created.dailyLimitMinutes = o.optInt("dailyLimitMinutes", 0);
                    db.appInfoDao().insert(created);
                } else {
                    existing.isStudyApp = o.optBoolean("isStudyApp", existing.isStudyApp);
                    String category = o.optString("category", null);
                    if (category != null && !category.isEmpty()) existing.category = category;
                    db.appInfoDao().update(existing);
                }
            }
        }

        JSONArray goals = root.optJSONArray("goals");
        if (goals != null) {
            for (int i = 0; i < goals.length(); i++) {
                JSONObject o = goals.optJSONObject(i);
                if (o == null) continue;
                long date = o.optLong("date", 0L);
                if (date <= 0L || db.goalDao().getGoalByDate(date) != null) continue;
                GoalEntity goal = new GoalEntity(date,
                        o.optInt("targetProductiveMins", 0),
                        o.optInt("actualProductiveMins", 0),
                        o.optInt("targetFocusSessions", 0),
                        o.optInt("actualFocusSessions", 0));
                db.goalDao().insert(goal);
            }
        }

        JSONArray achievements = root.optJSONArray("achievements");
        if (achievements != null) {
            for (int i = 0; i < achievements.length(); i++) {
                JSONObject o = achievements.optJSONObject(i);
                if (o == null) continue;
                String key = o.optString("key", "");
                if (key.isEmpty() || db.achievementDao().getByKey(key) != null) continue;
                AchievementEntity entity = new AchievementEntity(key,
                        o.optString("title", key),
                        o.optString("description", ""),
                        o.optBoolean("isUnlocked", false),
                        o.optLong("unlockedAt", 0L));
                db.achievementDao().insert(entity);
            }
        }

        JSONObject prefs = root.optJSONObject("prefs");
        if (prefs != null) {
            SharedPreferences.Editor editor = context
                    .getSharedPreferences("SelfPilotPrefs", Context.MODE_PRIVATE).edit();
            for (String key : PORTABLE_PREFS) {
                if (isDeviceSpecificKey(key) || !prefs.has(key)) continue;
                Object value = prefs.get(key);
                if (value instanceof Integer) editor.putInt(key, (Integer) value);
                else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else if (value instanceof String) editor.putString(key, (String) value);
            }
            editor.apply();
        }
    }

    /**
     * Guard used on both sides of the pipe. Permission / installation state is never portable,
     * so nothing with these prefixes can ever enter or leave a backup.
     */
    static boolean isDeviceSpecificKey(String key) {
        if (key == null) return true;
        String lower = key.toLowerCase();
        return lower.contains("permission")
                || lower.contains("accessibility")
                || lower.contains("notification_listener")
                || lower.contains("battery")
                || lower.contains("onboarding")
                || lower.contains("exact_alarm")
                || lower.contains("device");
    }
}
