package com.example.selfpilot.util;

import android.app.usage.UsageStats;
import android.content.Context;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.AppInfoDao;
import com.example.selfpilot.data.AppInfoEntity;
import com.example.selfpilot.data.FocusSessionEntity;
import com.example.selfpilot.data.HonorScoreDao;
import com.example.selfpilot.data.HonorScoreEntity;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ScoreCalculator {

    public static int calculateDisciplineScore(long nonStudyMinutes, long dailyBudgetMinutes) {
        int penalty = (int) (nonStudyMinutes / 5);
        if (dailyBudgetMinutes > 0 && nonStudyMinutes > dailyBudgetMinutes) {
            penalty += 15; // Exceeding budget penalty
        }
        return Math.max(0, 100 - penalty);
    }

    public static int calculateStudyPoints(long studyMinutes, int completedFocusSessions) {
        int points = (int) studyMinutes;
        points += (completedFocusSessions * 10); // 10 bonus points per completed session
        return points;
    }

    public static String determineHonorLevel(int disciplineScore, int streak) {
        if (disciplineScore >= 90 && streak >= 7) {
            return "Master";
        } else if (disciplineScore >= 80 && streak >= 3) {
            return "Disciplined";
        } else if (disciplineScore >= 70) {
            return "Focused";
        } else if (disciplineScore >= 50) {
            return "Consistent";
        } else if (disciplineScore >= 30) {
            return "Learner";
        } else {
            return "Beginner";
        }
    }

    public static void calculateAndSaveDailyScore(Context context) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long startOfDay = calendar.getTimeInMillis();
        long now = System.currentTimeMillis();

        Map<String, UsageStats> usageStatsMap = UsageUtil.getUsageStats(context, startOfDay, now);

        AppDatabase db = AppDatabase.getDatabase(context);
        AppInfoDao appInfoDao = db.appInfoDao();
        HonorScoreDao honorScoreDao = db.honorScoreDao();

        List<AppInfoEntity> allApps = appInfoDao.getAllApps();
        Map<String, AppInfoEntity> appInfoMap = new HashMap<>();
        if (allApps != null) {
            for (AppInfoEntity entity : allApps) {
                appInfoMap.put(entity.packageName, entity);
            }
        }

        long studyMinutes = 0;
        long nonStudyMinutes = 0;

        if (usageStatsMap != null) {
            for (Map.Entry<String, UsageStats> entry : usageStatsMap.entrySet()) {
                UsageStats stats = entry.getValue();
                long totalTimeInForeground = stats.getTotalTimeInForeground();
                if (totalTimeInForeground > 0) {
                    AppInfoEntity appInfo = appInfoMap.get(entry.getKey());
                    if (appInfo != null && appInfo.isStudyApp) {
                        studyMinutes += (totalTimeInForeground / 60000);
                    } else {
                        nonStudyMinutes += (totalTimeInForeground / 60000);
                    }
                }
            }
        }

        // Fetch completed focus sessions count today
        int completedSessions = 0;
        List<FocusSessionEntity> sessions = db.focusSessionDao().getSessionsByDate(startOfDay);
        if (sessions != null) {
            completedSessions = sessions.size();
        }

        int disciplineScore = calculateDisciplineScore(nonStudyMinutes, 240); // 4 hour budget default
        int studyPoints = calculateStudyPoints(studyMinutes, completedSessions);

        HonorScoreEntity todayScore = honorScoreDao.getScoreByDate(startOfDay);
        int currentStreak = 1;

        // Check yesterday's score to calculate streak
        long yesterdayStart = startOfDay - 86400000L;
        HonorScoreEntity yesterdayScore = honorScoreDao.getScoreByDate(yesterdayStart);
        if (yesterdayScore != null && yesterdayScore.getDisciplineScore() >= 70) {
            currentStreak = yesterdayScore.getStreak() + 1;
        }

        String honorLevel = determineHonorLevel(disciplineScore, currentStreak);

        if (todayScore == null) {
            todayScore = new HonorScoreEntity();
            todayScore.setDate(startOfDay);
            todayScore.setStudyPoints(studyPoints);
            todayScore.setDisciplineScore(disciplineScore);
            todayScore.setStreak(currentStreak);
            todayScore.setHonorLevel(honorLevel);
            honorScoreDao.insert(todayScore);
        } else {
            todayScore.setStudyPoints(studyPoints);
            todayScore.setDisciplineScore(disciplineScore);
            todayScore.setStreak(currentStreak);
            todayScore.setHonorLevel(honorLevel);
            honorScoreDao.update(todayScore);
        }
    }
}
