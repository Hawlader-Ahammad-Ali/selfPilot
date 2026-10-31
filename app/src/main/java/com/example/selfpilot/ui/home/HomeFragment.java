package com.example.selfpilot.ui.home;

import android.app.usage.UsageStats;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.AppInfoEntity;
import com.example.selfpilot.data.EmergencyUnlockEntity;
import com.example.selfpilot.data.ExamProfileEntity;
import com.example.selfpilot.data.FrozenAppEntity;
import com.example.selfpilot.data.HonorScoreDao;
import com.example.selfpilot.data.HonorScoreEntity;
import com.example.selfpilot.data.RestrictionRuleEntity;
import com.example.selfpilot.ui.exam.ExamModeActivity;
import com.example.selfpilot.ui.freezer.FreezerMainActivity;
import com.example.selfpilot.util.ScoreCalculator;
import com.example.selfpilot.util.UsageUtil;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class HomeFragment extends Fragment {

    private TextView tvTotalScreenTime;
    private LinearProgressIndicator progressScreenTimeBudget;
    private TextView tvScreenTimeBudget;
    private TextView tvStudyTimeHome;
    private TextView tvNonStudyTimeHome;
    private TextView tvDisciplineScoreHome;
    private TextView tvStudyPointsHome;
    private TextView tvRestrictedAppsHome;
    private TextView tvEmergencyUnlocksHome;

    private View btnShortcutApps;
    private View btnShortcutFocus;
    private View btnShortcutFreezer;
    private View btnShortcutExam;
    private View btnShortcutAnalytics;
    private View btnShortcutSettings;
    private View btnShortcutSettingsTop;

    private ExecutorService executorService;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        tvTotalScreenTime = view.findViewById(R.id.tvTotalScreenTime);
        progressScreenTimeBudget = view.findViewById(R.id.progressScreenTimeBudget);
        tvScreenTimeBudget = view.findViewById(R.id.tvScreenTimeBudget);
        tvStudyTimeHome = view.findViewById(R.id.tvStudyTimeHome);
        tvNonStudyTimeHome = view.findViewById(R.id.tvNonStudyTimeHome);
        tvDisciplineScoreHome = view.findViewById(R.id.tvDisciplineScoreHome);
        tvStudyPointsHome = view.findViewById(R.id.tvStudyPointsHome);
        tvRestrictedAppsHome = view.findViewById(R.id.tvRestrictedAppsHome);
        tvEmergencyUnlocksHome = view.findViewById(R.id.tvEmergencyUnlocksHome);

        btnShortcutApps = view.findViewById(R.id.cardActionManageApps);
        btnShortcutFocus = view.findViewById(R.id.cardActionStartFocus);
        btnShortcutFreezer = view.findViewById(R.id.cardActionFreezer);
        btnShortcutExam = view.findViewById(R.id.cardActionExamMode);
        btnShortcutAnalytics = view.findViewById(R.id.cardActionAnalytics);
        btnShortcutSettings = view.findViewById(R.id.cardActionSettings);
        btnShortcutSettingsTop = view.findViewById(R.id.btnShortcutSettingsTop);

        setupShortcuts(view);

        executorService = Executors.newSingleThreadExecutor();
        return view;
    }

    private void setupShortcuts(View root) {
        BottomNavigationView bottomNav = requireActivity().findViewById(R.id.bottom_nav);
        
        btnShortcutApps.setOnClickListener(v -> bottomNav.setSelectedItemId(R.id.navigation_apps));
        btnShortcutFocus.setOnClickListener(v -> bottomNav.setSelectedItemId(R.id.navigation_focus));
        btnShortcutAnalytics.setOnClickListener(v -> bottomNav.setSelectedItemId(R.id.navigation_analytics));
        btnShortcutSettings.setOnClickListener(v -> bottomNav.setSelectedItemId(R.id.navigation_settings));
        btnShortcutSettingsTop.setOnClickListener(v -> bottomNav.setSelectedItemId(R.id.navigation_settings));
        
        btnShortcutFreezer.setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), FreezerMainActivity.class);
            startActivity(intent);
        });
        btnShortcutExam.setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), ExamModeActivity.class);
            startActivity(intent);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        updateDashboard();
    }

    private void updateDashboard() {
        if (getContext() == null) return;

        executorService.execute(() -> {
            Context context = getContext();
            if (context == null) return;

            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            long startOfDay = calendar.getTimeInMillis();
            long now = System.currentTimeMillis();

            // 1. App Usage Stats (Study vs Non-Study)
            Map<String, UsageStats> statsMap = UsageUtil.getUsageStats(context, startOfDay, now);
            long totalMillis = 0;
            long studyMillis = 0;
            long nonStudyMillis = 0;

            AppDatabase db = AppDatabase.getDatabase(context);
            List<AppInfoEntity> appInfos = db.appInfoDao().getAllApps();
            Map<String, Boolean> studyMap = new HashMap<>();
            for (AppInfoEntity app : appInfos) {
                studyMap.put(app.packageName, app.isStudyApp);
            }

            if (statsMap != null) {
                for (Map.Entry<String, UsageStats> entry : statsMap.entrySet()) {
                    long time = entry.getValue().getTotalTimeInForeground();
                    if (time > 0) {
                        totalMillis += time;
                        Boolean isStudy = studyMap.get(entry.getKey());
                        if (isStudy != null && isStudy) {
                            studyMillis += time;
                        } else {
                            nonStudyMillis += time;
                        }
                    }
                }
            }

            long totalMins = totalMillis / 60000;
            long studyMins = studyMillis / 60000;
            long nonStudyMins = nonStudyMillis / 60000;

            long hours = totalMins / 60;
            long mins = totalMins % 60;
            String totalScreenTimeText = hours + "h " + mins + "m";

            // 2. Budget Limit
            SharedPreferences prefs = context.getSharedPreferences("SelfPilotPrefs", Context.MODE_PRIVATE);
            int dailyBudgetMins = prefs.getInt("daily_limit_minutes", 240); // default 4 hours
            long remainingMins = Math.max(0, dailyBudgetMins - totalMins);
            int budgetProgress = Math.min(100, (int) ((totalMins * 100) / Math.max(1, dailyBudgetMins)));

            String budgetText = "Daily limit • " + (dailyBudgetMins / 60) + "h / " + (remainingMins / 60) + "h " + (remainingMins % 60) + "m remaining";

            // 3. Honor Score & Calculation
            ScoreCalculator.calculateAndSaveDailyScore(context);
            HonorScoreDao honorScoreDao = db.honorScoreDao();
            HonorScoreEntity todayScore = honorScoreDao.getScoreByDate(startOfDay);

            String scoreVal = todayScore != null ? String.valueOf(todayScore.getDisciplineScore()) : "100";
            String pointsVal = todayScore != null ? String.valueOf(todayScore.getStudyPoints()) : "0";

            // 4. Restricted & Frozen Apps Count
            List<RestrictionRuleEntity> rules = db.restrictionRuleDao().getAllRules();
            int restrictedCount = 0;
            if (rules != null) {
                for (RestrictionRuleEntity r : rules) {
                    if (r.isBlocked) restrictedCount++;
                }
            }

            List<FrozenAppEntity> frozen = db.frozenAppDao().getFrozenApps();
            String selfPkg = requireContext().getPackageName();
            if (frozen != null) {
                for (FrozenAppEntity f : frozen) {
                    if (f.isFrozen && !f.packageName.equals("com.example.selfpilot") && !f.packageName.equals(selfPkg)) {
                        restrictedCount++;
                    }
                }
            }

            EmergencyUnlockEntity unlockEntity = db.emergencyUnlockDao().getUnlockByDate(startOfDay);
            int usedUnlocks = unlockEntity != null ? unlockEntity.unlocksUsed : 0;
            int remainingUnlocks = Math.max(0, 3 - usedUnlocks);

            final int finalRestrictedCount = restrictedCount;
            final int finalRemainingUnlocks = remainingUnlocks;

            // Update UI
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    tvTotalScreenTime.setText(totalScreenTimeText);
                    progressScreenTimeBudget.setProgress(budgetProgress);
                    tvScreenTimeBudget.setText(budgetText);
                    tvStudyTimeHome.setText(studyMins + "m");
                    tvNonStudyTimeHome.setText(nonStudyMins + "m");
                    tvDisciplineScoreHome.setText(scoreVal);
                    tvStudyPointsHome.setText(pointsVal);
                    tvRestrictedAppsHome.setText(String.valueOf(finalRestrictedCount));
                    tvEmergencyUnlocksHome.setText(usedUnlocks + " / 3");
                });
            }
        });
    }

    private void showAnalyticsDialog() {
        if (getContext() == null) return;
        executorService.execute(() -> {
            Context context = getContext();
            if (context == null) return;

            long now = System.currentTimeMillis();
            long sevenDaysAgo = now - (7 * 86400000L);
            Map<String, UsageStats> stats = UsageUtil.getUsageStats(context, sevenDaysAgo, now);

            long total7DaysMillis = 0;
            if (stats != null) {
                for (UsageStats s : stats.values()) {
                    total7DaysMillis += s.getTotalTimeInForeground();
                }
            }

            long total7DaysHours = (total7DaysMillis / 60000) / 60;
            long avgDailyHours = total7DaysHours / 7;

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("7-Day Usage Analytics")
                            .setMessage("Past 7 Days Screen Time: " + total7DaysHours + " hrs\nAverage Daily Screen Time: " + avgDailyHours + " hrs/day\n\nKeep maintaining study focus to improve your Honor Level!")
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}
