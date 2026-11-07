package com.example.selfpilot.account;

import android.content.Context;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.HonorScoreEntity;
import com.example.selfpilot.data.RestrictionRuleEntity;

/**
 * Aggregate numbers for the profile dashboard. Read straight from Room, so the dashboard is
 * identical for guests and members - the account only decides whether the numbers are also
 * copied to the cloud.
 */
public final class ProfileStats {

    public long studyPoints;
    public int focusMinutes;
    public int focusSessions;
    public int exams;
    public int achievements;
    public int streak;
    public int discipline = 100;
    public int restrictedApps;

    private ProfileStats() {
    }

    public static ProfileStats load(Context context) {
        ProfileStats stats = new ProfileStats();
        if (context == null) return stats;
        try {
            AppDatabase db = AppDatabase.getDatabase(context);

            stats.studyPoints = db.honorScoreDao().getTotalStudyPoints();
            stats.focusMinutes = db.focusSessionDao().getTotalFocusMinutes();
            stats.focusSessions = db.focusSessionDao().getTotalSessions();

            HonorScoreEntity latest = db.honorScoreDao().getLatestScore();
            if (latest != null) {
                stats.discipline = latest.getDisciplineScore();
                stats.streak = latest.getStreak();
            }

            if (db.examProfileDao().getAllExams() != null) {
                stats.exams = db.examProfileDao().getAllExams().size();
            }
            if (db.achievementDao().getAllAchievements() != null) {
                stats.achievements = db.achievementDao().getAllAchievements().size();
            }

            int restricted = 0;
            java.util.List<RestrictionRuleEntity> rules = db.restrictionRuleDao().getAllRules();
            if (rules != null) {
                for (RestrictionRuleEntity rule : rules) {
                    if (rule.isBlocked) restricted++;
                }
            }
            stats.restrictedApps = restricted + db.frozenAppDao().getFrozenCount();
        } catch (Exception ignored) {
        }
        return stats;
    }
}
