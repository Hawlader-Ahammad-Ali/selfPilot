package com.example.selfpilot.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.Fragment;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.AppInfoEntity;
import com.example.selfpilot.data.HonorScoreEntity;
import com.example.selfpilot.ui.exam.ExamModeActivity;
import com.example.selfpilot.ui.freezer.FreezerMainActivity;
import com.example.selfpilot.util.UsageUtil;

import java.util.Calendar;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsFragment extends Fragment {

    private TextView tvUsagePermissionStatus;
    private TextView tvAccessibilityStatus;
    private TextView tvNotificationStatus;
    private View btnOpenUsageAccess;
    private View btnEnableAppLock;
    private View btnOpenNotificationAccess;
    private EditText etDailyBudgetMins;
    private View btnSaveBudget;
    private View btnOpenExamModeSettings;
    private View btnExportData;
    private View btnResetData;
    private View btnAppFreezerSettings;
    private View rowGithubProfile;

    private ExecutorService executorService;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        tvUsagePermissionStatus = view.findViewById(R.id.tvUsagePermissionStatus);
        tvAccessibilityStatus = view.findViewById(R.id.tvAccessibilityStatus);
        tvNotificationStatus = view.findViewById(R.id.tvNotificationStatus);
        
        btnOpenUsageAccess = view.findViewById(R.id.rowUsageAccess);
        btnEnableAppLock = view.findViewById(R.id.rowAppLockAccessibility);
        btnOpenNotificationAccess = view.findViewById(R.id.rowNotificationAccess);
        
        etDailyBudgetMins = view.findViewById(R.id.etDailyBudgetMins);
        btnSaveBudget = view.findViewById(R.id.btnSaveBudget);
        btnOpenExamModeSettings = view.findViewById(R.id.rowConfigureExamMode);
        btnExportData = view.findViewById(R.id.rowExportUsageSummary);
        btnResetData = view.findViewById(R.id.rowResetAppData);
        btnAppFreezerSettings = view.findViewById(R.id.rowManageAppFreezer);
        rowGithubProfile = view.findViewById(R.id.rowGithubProfile);

        executorService = Executors.newSingleThreadExecutor();

        setupListeners();
        loadSettings();

        return view;
    }

    private void setupListeners() {
        btnOpenUsageAccess.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            startActivity(intent);
        });

        btnEnableAppLock.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
        
        if (btnOpenNotificationAccess != null) {
            btnOpenNotificationAccess.setOnClickListener(v -> {
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                startActivity(intent);
            });
        }

        btnSaveBudget.setOnClickListener(v -> {
            String str = etDailyBudgetMins.getText().toString().trim();
            int mins = 240;
            try {
                mins = Integer.parseInt(str);
            } catch (Exception ignored) {
            }

            SharedPreferences prefs = requireContext().getSharedPreferences("SelfPilotPrefs", Context.MODE_PRIVATE);
            prefs.edit().putInt("daily_limit_minutes", mins).apply();
            Toast.makeText(getContext(), "Daily Budget updated to " + mins + " minutes.", Toast.LENGTH_SHORT).show();
        });

        btnOpenExamModeSettings.setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), ExamModeActivity.class);
            startActivity(intent);
        });

        if (btnAppFreezerSettings != null) {
            btnAppFreezerSettings.setOnClickListener(v -> {
                Intent intent = new Intent(getContext(), FreezerMainActivity.class);
                startActivity(intent);
            });
        }

        btnExportData.setOnClickListener(v -> exportDataJSON());

        btnResetData.setOnClickListener(v -> resetAppData());

        rowGithubProfile.setOnClickListener(v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Hawlader-Ahammad-Ali"));
            startActivity(browserIntent);
        });
    }

    private void loadSettings() {
        if (getContext() == null) return;
        SharedPreferences prefs = requireContext().getSharedPreferences("SelfPilotPrefs", Context.MODE_PRIVATE);
        int budget = prefs.getInt("daily_limit_minutes", 240);
        etDailyBudgetMins.setText(String.valueOf(budget));
    }

    @Override
    public void onResume() {
        super.onResume();
        checkPermissionsStatus();
    }

    private void checkPermissionsStatus() {
        if (getContext() == null) return;

        boolean usageGranted = UsageUtil.hasUsageStatsPermission(getContext());
        if (usageGranted) {
            tvUsagePermissionStatus.setText("Granted (Active)");
            tvUsagePermissionStatus.setTextColor(getResources().getColor(R.color.success_green, null));
        } else {
            tvUsagePermissionStatus.setText("Not Granted (Tap to enable)");
            tvUsagePermissionStatus.setTextColor(getResources().getColor(R.color.warning_yellow, null));
        }

        tvAccessibilityStatus.setText("Configured via Settings");
        tvAccessibilityStatus.setTextColor(getResources().getColor(R.color.electric_blue, null));
        
        if (tvNotificationStatus != null) {
            Set<String> listeners = NotificationManagerCompat.getEnabledListenerPackages(requireContext());
            boolean hasNotificationAccess = listeners.contains(requireContext().getPackageName());
            if (hasNotificationAccess) {
                tvNotificationStatus.setText("Granted (Active)");
                tvNotificationStatus.setTextColor(getResources().getColor(R.color.success_green, null));
            } else {
                tvNotificationStatus.setText("Not Granted (Tap to enable)");
                tvNotificationStatus.setTextColor(getResources().getColor(R.color.warning_yellow, null));
            }
        }
    }

    private void exportDataJSON() {
        if (getContext() == null) return;
        executorService.execute(() -> {
            AppDatabase db = AppDatabase.getDatabase(getContext());
            List<AppInfoEntity> apps = db.appInfoDao().getAllApps();

            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            long startOfDay = calendar.getTimeInMillis();

            HonorScoreEntity score = db.honorScoreDao().getScoreByDate(startOfDay);

            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"appName\": \"SelfPilot\",\n");
            json.append("  \"exportTimestamp\": ").append(System.currentTimeMillis()).append(",\n");
            json.append("  \"todayDisciplineScore\": ").append(score != null ? score.getDisciplineScore() : 100).append(",\n");
            json.append("  \"todayStudyPoints\": ").append(score != null ? score.getStudyPoints() : 0).append(",\n");
            json.append("  \"installedAppsCount\": ").append(apps != null ? apps.size() : 0).append("\n");
            json.append("}");

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Exported Data (JSON)")
                            .setMessage(json.toString())
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    private void resetAppData() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Reset App Data?")
                .setMessage("This will clear all local score history, custom app classifications, and focus sessions. Are you sure?")
                .setPositiveButton("Reset Everything", (d, w) -> {
                    executorService.execute(() -> {
                        AppDatabase db = AppDatabase.getDatabase(requireContext());
                        db.clearAllTables();

                        SharedPreferences prefs = requireContext().getSharedPreferences("SelfPilotPrefs", Context.MODE_PRIVATE);
                        prefs.edit().clear().apply();

                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                Toast.makeText(getContext(), "App data reset successfully.", Toast.LENGTH_SHORT).show();
                                loadSettings();
                            });
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}
