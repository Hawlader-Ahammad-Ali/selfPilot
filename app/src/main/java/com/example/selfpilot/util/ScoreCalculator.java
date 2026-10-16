package com.example.selfpilot.util;

import android.app.usage.UsageStats;
import android.content.Context;

import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.AppInfoDao;
import com.example.selfpilot.data.AppInfoEntity;
import com.example.selfpilot.data.HonorScoreDao;
import com.example.selfpilot.data.HonorScoreEntity;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ScoreCalculator {

    public static void calculateAndSaveDailyScore(Context context) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long startOfDay = calendar.getTimeInMillis();
        long now = System.currentTimeMillis();

        Map<String, UsageStats> usageStatsMap = UsageUtil.getUsageStats(context, startOfDay, now);
        if (usageStatsMap == null || usageStatsMap.isEmpty()) return;

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

        long studyPoints = 0;
        long distractingMinutes = 0;

        for (Map.Entry<String, UsageStats> entry : usageStatsMap.entrySet()) {
            UsageStats stats = entry.getValue();
            long totalTimeInForeground = stats.getTotalTimeInForeground();
            if (totalTimeInForeground > 0) {
                AppInfoEntity appInfo = appInfoMap.get(entry.getKey());
                if (appInfo != null) {
                    if (appInfo.isStudyApp) {
                        studyPoints += (totalTimeInForeground / 60000);
                    } else {
                        distractingMinutes += (totalTimeInForeground / 60000);
                    }
                } else {
                    distractingMinutes += (totalTimeInForeground / 60000);
                }
            }
        }

        int disciplineScore = Math.max(0, 100 - (int) (distractingMinutes / 5));

        HonorScoreEntity todayScore = honorScoreDao.getScoreByDate(startOfDay);
        if (todayScore == null) {
            todayScore = new HonorScoreEntity();
            todayScore.setDate(startOfDay);
            todayScore.setStudyPoints((int) studyPoints);
            todayScore.setDisciplineScore(disciplineScore);
            honorScoreDao.insert(todayScore);
        } else {
            todayScore.setStudyPoints((int) studyPoints);
            todayScore.setDisciplineScore(disciplineScore);
            honorScoreDao.update(todayScore);
        }
    }
}
