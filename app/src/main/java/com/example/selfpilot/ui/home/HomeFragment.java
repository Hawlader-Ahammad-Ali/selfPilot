package com.example.selfpilot.ui.home;

import android.app.usage.UsageStats;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

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
import com.example.selfpilot.data.HonorScoreDao;
import com.example.selfpilot.data.HonorScoreEntity;
import com.example.selfpilot.data.RestrictionRuleEntity;
import com.example.selfpilot.util.ScoreCalculator;
import com.example.selfpilot.util.UsageUtil;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeFragment extends Fragment {

    private TextView tvHomeStreakLevel;
    private TextView tvHomeModeStatus;
    private TextView tvTotalScreenTime;
    private LinearProgressIndicator progressScreenTimeBudget;
    private TextView tvScreenTimeBudget;
    private TextView tvStudyTimeHome;
    private TextView tvNonStudyTimeHome;
    private TextView tvDisciplineScoreHome;
    private TextView tvStudyPointsHome;
    private TextView tvRestrictedAppsHome;
    private TextView tvEmergencyUnlocksHome;

    private Button btnShortcutApps;
    private Button btnShortcutFocus;
    private Button btnShortcutFreezer;
    private Button btnShortcutExam;
    private Button btnShortcutAnalytics;
    private Button btnShortcutSettings;

    private ExecutorService executorService;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        
        tvHomeStreakLevel = view.findViewById(R.id.tvHomeStreakLevel);
        tvHomeModeStatus = view.findViewById(R.id.tvHomeModeStatus);
        tvTotalScreenTime = view.findViewById(R.id.tvTotalScreenTime);
        progressScreenTimeBudget = view.findViewById(R.id.progressScreenTimeBudget);
        tvScreenTimeBudget = view.findViewById(R.id.tvScreenTimeBudget);
        tvStudyTimeHome = view.findViewById(R.id.tvStudyTimeHome);
        tvNonStudyTimeHome = view.findViewById(R.id.tvNonStudyTimeHome);
        tvDisciplineScoreHome = view.findViewById(R.id.tvDisciplineScoreHome);
        tvStudyPointsHome = view.findViewById(R.id.tvStudyPointsHome);
        tvRestrictedAppsHome = view.findViewById(R.id.tvRestrictedAppsHome);
        tvEmergencyUnlocksHome = view.findViewById(R.id.tvEmergencyUnlocksHome);

        btnShortcutApps = view.findViewById(R.id.btnShortcutApps);
        btnShortcutFocus = view.findViewById(R.id.btnShortcutFocus);
        btnShortcutFreezer = view.findViewById(R.id.btnShortcutFreezer);
        btnShortcutExam = view.findViewById(R.id.btnShortcutExam);
        btnShortcutAnalytics = view.findViewById(R.id.btnShortcutAnalytics);
        btnShortcutSettings = view.findViewById(R.id.btnShortcutSettings);

        setupShortcuts(view);

        executorService = Executors.newSingleThreadExecutor();
        return view;
    }

    private void setupShortcuts(View root) {
        btnShortcutApps.setOnClickListener(v -> Navigation.findNavController(root).navigate(R.id.navigation_apps));
        btnShortcutFocus.setOnClickListener(v -> Navigation.findNavController(root).navigate(R.id.navigation_focus));
        btnShortcutFreezer.setOnClickListener(v -> showAppFreezerDialog());
        btnShortcutExam.setOnClickListener(v -> showExamModeDialog());
        btnShortcutAnalytics.setOnClickListener(v -> showAnalyticsDialog());
        btnShortcutSettings.setOnClickListener(v -> Navigation.findNavController(root).navigate(R.id.navigation_settings));
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
            String totalScreenTimeText = hours + " hrs " + mins + " mins";

            // 2. Budget Limit
            SharedPreferences prefs = context.getSharedPreferences("SelfPilotPrefs", Context.MODE_PRIVATE);
            int dailyBudgetMins = prefs.getInt("daily_limit_minutes", 240); // default 4 hours
            long remainingMins = Math.max(0, dailyBudgetMins - totalMins);
            int budgetProgress = Math.min(100, (int) ((totalMins * 100) / Math.max(1, dailyBudgetMins)));

            String budgetText = "Daily Limit: " + (dailyBudgetMins / 60) + "h " + (dailyBudgetMins % 60) + "m (Remaining: " + (remainingMins / 60) + "h " + (remainingMins % 60) + "m)";

            // 3. Honor Score & Calculation
            ScoreCalculator.calculateAndSaveDailyScore(context);
            HonorScoreDao honorScoreDao = db.honorScoreDao();
            HonorScoreEntity todayScore = honorScoreDao.getScoreByDate(startOfDay);

            String levelText = "Level: " + (todayScore != null ? todayScore.getHonorLevel() : "Beginner") + " • " + (todayScore != null ? todayScore.getStreak() : 1) + "-Day Streak";
            String scoreVal = todayScore != null ? String.valueOf(todayScore.getDisciplineScore()) : "100";
            String pointsVal = todayScore != null ? todayScore.getStudyPoints() + " pts" : "0 pts";

            // 4. Restricted Apps Count & Emergency Unlocks
            List<RestrictionRuleEntity> rules = db.restrictionRuleDao().getAllRules();
            int restrictedCount = 0;
            if (rules != null) {
                for (RestrictionRuleEntity r : rules) {
                    if (r.isBlocked) restrictedCount++;
                }
            }

            EmergencyUnlockEntity unlockEntity = db.emergencyUnlockDao().getUnlockByDate(startOfDay);
            int usedUnlocks = unlockEntity != null ? unlockEntity.unlocksUsed : 0;
            int remainingUnlocks = Math.max(0, 3 - usedUnlocks);

            // 5. Active Exam / Study Mode status
            ExamProfileEntity activeExam = db.examProfileDao().getActiveExam();
            String modeStatusText = (activeExam != null && activeExam.isActive)
                    ? "Mode: EXAM MODE Active (" + activeExam.examName + ")"
                    : "Mode: Standard Study Mode";

            final int finalRestrictedCount = restrictedCount;
            final int finalRemainingUnlocks = remainingUnlocks;

            // Update UI
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    tvHomeStreakLevel.setText(levelText);
                    tvHomeModeStatus.setText(modeStatusText);
                    tvTotalScreenTime.setText(totalScreenTimeText);
                    progressScreenTimeBudget.setProgress(budgetProgress);
                    tvScreenTimeBudget.setText(budgetText);
                    tvStudyTimeHome.setText(studyMins + "m");
                    tvNonStudyTimeHome.setText(nonStudyMins + "m");
                    tvDisciplineScoreHome.setText(scoreVal);
                    tvStudyPointsHome.setText(pointsVal);
                    tvRestrictedAppsHome.setText(finalRestrictedCount + " Apps");
                    tvEmergencyUnlocksHome.setText(finalRemainingUnlocks + " / 3 Left");
                });
            }
        });
    }

    private void showAppFreezerDialog() {
        if (getContext() == null) return;
        executorService.execute(() -> {
            AppDatabase db = AppDatabase.getDatabase(getContext());
            List<AppInfoEntity> apps = db.appInfoDao().getAllApps();
            StringBuilder sb = new StringBuilder();
            int frozenCount = 0;
            for (AppInfoEntity app : apps) {
                if (app.isFrozen) {
                    frozenCount++;
                    sb.append("• ").append(app.appName).append("\n");
                }
            }

            String msg = frozenCount > 0
                    ? "Frozen Applications (" + frozenCount + "):\n\n" + sb.toString() + "\nManage frozen apps in the Apps tab."
                    : "No applications currently frozen.\n\nYou can freeze distracting apps from the Apps tab.";

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("App Freezer")
                            .setMessage(msg)
                            .setPositiveButton("Go To Apps", (d, w) -> {
                                View view = getView();
                                if (view != null) {
                                    Navigation.findNavController(view).navigate(R.id.navigation_apps);
                                }
                            })
                            .setNegativeButton("Close", null)
                            .show();
                });
            }
        });
    }

    private void showExamModeDialog() {
        if (getContext() == null) return;
        executorService.execute(() -> {
            AppDatabase db = AppDatabase.getDatabase(getContext());
            ExamProfileEntity active = db.examProfileDao().getActiveExam();

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
                    builder.setTitle("Exam Mode Configuration");

                    if (active != null && active.isActive) {
                        builder.setMessage("Active Exam: " + active.examName + "\nDaily Goal: " + active.dailyGoalMinutes + " mins study\n\nExam Mode enforces strict restrictions on distracting applications.");
                        builder.setNegativeButton("Deactivate Exam Mode", (d, w) -> {
                            executorService.execute(() -> {
                                db.examProfileDao().deactivateAllExams();
                                updateDashboard();
                            });
                        });
                        builder.setPositiveButton("Close", null);
                    } else {
                        builder.setMessage("No active exam profile.\n\nActivating Exam Mode applies heightened discipline rules and restricts entertainment apps during exam prep.");
                        builder.setPositiveButton("Activate Mock Exam", (d, w) -> {
                            executorService.execute(() -> {
                                db.examProfileDao().deactivateAllExams();
                                ExamProfileEntity mock = new ExamProfileEntity("Final Examinations", System.currentTimeMillis(), System.currentTimeMillis() + 7 * 86400000L, 180, true);
                                db.examProfileDao().insert(mock);
                                updateDashboard();
                            });
                        });
                        builder.setNegativeButton("Cancel", null);
                    }
                    builder.show();
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
