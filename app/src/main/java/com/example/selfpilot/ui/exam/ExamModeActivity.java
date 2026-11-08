package com.example.selfpilot.ui.exam;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import com.example.selfpilot.MainActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.ExamProfileEntity;
import com.example.selfpilot.util.ExamScheduler;
import com.example.selfpilot.util.UsageUtil;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ExamModeActivity extends AppCompatActivity {

    private EditText etExamName;
    private EditText etExamDailyGoal;
    private View btnPickStartDate;
    private View btnPickEndDate;
    private View btnSaveExam;

    private View btnSelectRestrictedApps;
    private View btnSelectAllowedApps;
    private TextView tvStartDateText;
    private TextView tvEndDateText;
    private TextView tvRestrictedCount;
    private TextView tvAllowedCount;

    private MaterialCardView cardActiveExam;
    private TextView tvActiveExamName;
    private TextView tvActiveExamGoal;
    private Button btnCancelActiveExam;

    private com.google.android.material.materialswitch.MaterialSwitch switchAllowCalls;
    private com.google.android.material.materialswitch.MaterialSwitch switchAllowMessages;

    private AppDatabase db;
    private ExecutorService executorService;

    private long selectedStartDate = 0;
    private long selectedEndDate = 0;

    private List<String> selectedRestrictedPackages = new ArrayList<>();
    private List<String> selectedAllowedPackages = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_exam_mode);
        com.example.selfpilot.util.SystemBarInsets.apply(findViewById(R.id.exam_mode_root));

        etExamName = findViewById(R.id.etExamName);
        etExamDailyGoal = findViewById(R.id.etExamDailyGoal);
        btnPickStartDate = findViewById(R.id.btnPickStartDate);
        btnPickEndDate = findViewById(R.id.btnPickEndDate);
        btnSaveExam = findViewById(R.id.btnSaveExam);
        btnSelectRestrictedApps = findViewById(R.id.btnSelectRestrictedApps);
        btnSelectAllowedApps = findViewById(R.id.btnSelectAllowedApps);
        tvStartDateText = findViewById(R.id.tvStartDateText);
        tvEndDateText = findViewById(R.id.tvEndDateText);
        tvRestrictedCount = findViewById(R.id.tvRestrictedCount);
        tvAllowedCount = findViewById(R.id.tvAllowedCount);

        cardActiveExam = findViewById(R.id.cardActiveExam);
        tvActiveExamName = findViewById(R.id.tvActiveExamName);
        tvActiveExamGoal = findViewById(R.id.tvActiveExamGoal);
        btnCancelActiveExam = findViewById(R.id.btnCancelActiveExam);
        switchAllowCalls = findViewById(R.id.switchAllowCalls);
        switchAllowMessages = findViewById(R.id.switchAllowMessages);

        // Load SharedPreferences for switches
        android.content.SharedPreferences prefs = getSharedPreferences("SelfPilotPrefs", MODE_PRIVATE);
        switchAllowCalls.setChecked(prefs.getBoolean("exam_allow_calls", true));
        switchAllowMessages.setChecked(prefs.getBoolean("exam_allow_messages", false));

        switchAllowCalls.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("exam_allow_calls", isChecked).apply();
        });

        switchAllowMessages.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("exam_allow_messages", isChecked).apply();
        });

        ImageView btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.getMenu().setGroupCheckable(0, false, true);
        bottomNav.setOnItemSelectedListener(item -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("NAVIGATE_TO", item.getItemId());
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
            return false;
        });

        db = AppDatabase.getDatabase(getApplicationContext());
        executorService = Executors.newSingleThreadExecutor();

        loadActiveExam();

        btnPickStartDate.setOnClickListener(v -> showDatePicker(true));
        btnPickEndDate.setOnClickListener(v -> showDatePicker(false));
        btnSaveExam.setOnClickListener(v -> saveExamProfile());
        
        btnSelectRestrictedApps.setOnClickListener(v -> showAppSelectionDialog("Select Apps to Restrict", selectedRestrictedPackages, newSelected -> {
            selectedRestrictedPackages = newSelected;
            if (tvRestrictedCount != null) tvRestrictedCount.setText(newSelected.size() + " selected");
        }));

        btnSelectAllowedApps.setOnClickListener(v -> showAppSelectionDialog("Select Apps to Allow", selectedAllowedPackages, newSelected -> {
            selectedAllowedPackages = newSelected;
            if (tvAllowedCount != null) tvAllowedCount.setText(newSelected.size() + " selected");
        }));

        btnCancelActiveExam.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Cancel Exam Mode?")
                .setMessage("This cancels the active and any scheduled exam profiles, and removes their alarms.")
                .setPositiveButton("Cancel Exam Mode", (dialog, which) -> cancelExamMode())
                .setNegativeButton("Keep", null)
                .show());
    }

    /**
     * Start date is the beginning of the chosen day, end date is the END of the chosen day.
     * Using midnight for both made every exam end one day early (an exam ending "on the 5th"
     * actually ended at 00:00 of the 5th instead of covering that whole day).
     */
    private void showDatePicker(boolean isStart) {
        Calendar cal = Calendar.getInstance();
        long current = isStart ? selectedStartDate : selectedEndDate;
        if (current > 0) {
            cal.setTimeInMillis(current);
        }

        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            Calendar selected = Calendar.getInstance();
            if (isStart) {
                selected.set(year, month, dayOfMonth, 0, 0, 0);
                selected.set(Calendar.MILLISECOND, 0);
                selectedStartDate = selected.getTimeInMillis();
                if (tvStartDateText != null) {
                    tvStartDateText.setText(formatDate(selectedStartDate) + " 00:00");
                }
            } else {
                selected.set(year, month, dayOfMonth, 23, 59, 59);
                selected.set(Calendar.MILLISECOND, 999);
                selectedEndDate = selected.getTimeInMillis();
                if (tvEndDateText != null) {
                    tvEndDateText.setText(formatDate(selectedEndDate) + " 23:59");
                }
            }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private String formatDate(long timeMillis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timeMillis);
        String month = String.format(java.util.Locale.US, "%02d", calendar.get(Calendar.MONTH) + 1);
        String day = String.format(java.util.Locale.US, "%02d", calendar.get(Calendar.DAY_OF_MONTH));
        return calendar.get(Calendar.YEAR) + "-" + month + "-" + day;
    }

    private void loadActiveExam() {
        executorService.execute(() -> {
            ExamProfileEntity active = null;
            List<ExamProfileEntity> activeExams = db.examProfileDao().getActiveExams();
            if (activeExams != null && !activeExams.isEmpty()) {
                active = activeExams.get(0);
            } else {
                List<ExamProfileEntity> pendingExams = db.examProfileDao().getPendingExams();
                if (pendingExams != null && !pendingExams.isEmpty()) {
                    active = pendingExams.get(0);
                }
            }

            final ExamProfileEntity finalActive = active;
            runOnUiThread(() -> {
                if (finalActive != null) {
                    cardActiveExam.setVisibility(View.VISIBLE);
                    tvActiveExamName.setText(finalActive.examName + " (" + finalActive.status + ")");
                    tvActiveExamGoal.setText("Daily Goal: " + finalActive.dailyGoalMinutes + " Mins");

                    if (finalActive.restrictedAppsCsv != null && !finalActive.restrictedAppsCsv.isEmpty()) {
                        selectedRestrictedPackages = new ArrayList<>(Arrays.asList(finalActive.restrictedAppsCsv.split(",")));
                        if (tvRestrictedCount != null) tvRestrictedCount.setText(selectedRestrictedPackages.size() + " selected");
                    }
                    if (finalActive.allowedAppsCsv != null && !finalActive.allowedAppsCsv.isEmpty()) {
                        selectedAllowedPackages = new ArrayList<>(Arrays.asList(finalActive.allowedAppsCsv.split(",")));
                        if (tvAllowedCount != null) tvAllowedCount.setText(selectedAllowedPackages.size() + " selected");
                    }
                    etExamName.setText(finalActive.examName);
                    etExamDailyGoal.setText(String.valueOf(finalActive.dailyGoalMinutes));
                    
                    selectedStartDate = finalActive.startDate;
                    if (tvStartDateText != null) {
                        tvStartDateText.setText(formatDate(finalActive.startDate) + " 00:00");
                    }

                    selectedEndDate = finalActive.endDate;
                    if (tvEndDateText != null) {
                        tvEndDateText.setText(formatDate(finalActive.endDate) + " 23:59");
                    }
                } else {
                    cardActiveExam.setVisibility(View.GONE);
                }
            });
        });
    }

    private void showAppSelectionDialog(String title, List<String> currentlySelected, OnSelectionConfirmedListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_select_apps, null);
        builder.setView(dialogView);

        EditText etSearchApp = dialogView.findViewById(R.id.etSearchApp);
        Button btnSelectAll = dialogView.findViewById(R.id.btnSelectAll);
        Button btnClearAll = dialogView.findViewById(R.id.btnClearAll);
        ProgressBar pbLoading = dialogView.findViewById(R.id.pbLoading);
        RecyclerView rvApps = dialogView.findViewById(R.id.rvApps);

        rvApps.setLayoutManager(new LinearLayoutManager(this));
        SelectableAppAdapter adapter = new SelectableAppAdapter();
        rvApps.setAdapter(adapter);

        AlertDialog dialog = builder.setPositiveButton("Confirm Selection", (d, w) -> {
            listener.onConfirm(adapter.getSelectedPackages());
        }).setNegativeButton("Cancel", null).create();

        pbLoading.setVisibility(View.VISIBLE);
        rvApps.setVisibility(View.GONE);

        executorService.execute(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> installedApps = UsageUtil.getInstalledApps(getApplicationContext());
            String ownPackage = getPackageName();

            List<SelectableAppAdapter.AppItem> items = new ArrayList<>();
            for (ApplicationInfo appInfo : installedApps) {
                if (appInfo.packageName.equals(ownPackage)) continue;

                String name = UsageUtil.safeLabel(pm, appInfo);
                Drawable icon = null;
                try {
                    icon = pm.getApplicationIcon(appInfo.packageName);
                } catch (PackageManager.NameNotFoundException ignored) {}
                
                boolean isSelected = currentlySelected.contains(appInfo.packageName);
                items.add(new SelectableAppAdapter.AppItem(appInfo.packageName, name, icon, isSelected));
            }

            runOnUiThread(() -> {
                pbLoading.setVisibility(View.GONE);
                rvApps.setVisibility(View.VISIBLE);
                adapter.setAppList(items);

                etSearchApp.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        adapter.filter(s.toString().trim());
                    }

                    @Override
                    public void afterTextChanged(Editable s) {}
                });

                btnSelectAll.setOnClickListener(v -> adapter.selectAll());
                btnClearAll.setOnClickListener(v -> adapter.clearAll());
            });
        });

        dialog.show();
    }

    interface OnSelectionConfirmedListener {
        void onConfirm(List<String> selectedPackages);
    }

    /**
     * Cancels exam mode AND its pending alarms. Previously only the database rows were
     * touched, so a cancelled exam could still fire its start alarm later.
     */
    private void cancelExamMode() {
        executorService.execute(() -> {
            try {
                List<ExamProfileEntity> exams = db.examProfileDao().getAllExams();
                if (exams != null) {
                    for (ExamProfileEntity exam : exams) {
                        if (exam == null || exam.status == null) continue;
                        if ("COMPLETED".equals(exam.status) || "CANCELLED".equals(exam.status)) continue;
                        ExamScheduler.cancelExamAlarms(getApplicationContext(), exam.id);
                    }
                }
                db.examProfileDao().deactivateAllExams();
            } catch (Exception e) {
                android.util.Log.e("ExamModeActivity", "Failed to cancel exam mode", e);
            }

            runOnUiThread(() -> {
                Toast.makeText(this, "Exam Mode cancelled.", Toast.LENGTH_SHORT).show();
                loadActiveExam();
            });
        });
    }

    private void saveExamProfile() {
        String name = etExamName.getText().toString().trim();
        String goalStr = etExamDailyGoal.getText().toString().trim();

        if (name.isEmpty() || selectedStartDate == 0 || selectedEndDate == 0) {
            Toast.makeText(this, "Please enter exam name and select valid dates.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (selectedEndDate <= selectedStartDate) {
            Toast.makeText(this, "End date must be on or after the start date.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (selectedEndDate < System.currentTimeMillis()) {
            Toast.makeText(this, "End date is already in the past.", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (selectedRestrictedPackages.isEmpty()) {
            Toast.makeText(this, "Please select at least one app to restrict.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check for conflicts between restricted and allowed
        for (String restrictedPkg : selectedRestrictedPackages) {
            if (selectedAllowedPackages.contains(restrictedPkg)) {
                Toast.makeText(this, "Conflict: An app cannot be both Restricted and Allowed.", Toast.LENGTH_LONG).show();
                return;
            }
        }

        String restrictedCsv = String.join(",", selectedRestrictedPackages);
        String allowedCsv = String.join(",", selectedAllowedPackages);

        int goalMins = 180;
        try {
            goalMins = Integer.parseInt(goalStr);
        } catch (Exception ignored) {
        }

        int finalGoalMins = goalMins;
        executorService.execute(() -> {
            // Only one exam can be scheduled at a time. Without this, re-saving the prefilled
            // form created a duplicate profile that activated on top of the real one.
            List<ExamProfileEntity> pending = db.examProfileDao().getPendingExams();
            if (pending != null) {
                for (ExamProfileEntity old : pending) {
                    if (old == null) continue;
                    ExamScheduler.cancelExamAlarms(getApplicationContext(), old.id);
                    old.status = "CANCELLED";
                    old.isActive = false;
                    db.examProfileDao().update(old);
                }
            }

            ExamProfileEntity newExam = new ExamProfileEntity(
                    name,
                    selectedStartDate,
                    selectedEndDate,
                    finalGoalMins,
                    restrictedCsv,
                    allowedCsv
            );
            long newId = db.examProfileDao().insert(newExam);
            newExam.id = (int) newId;

            ExamScheduler.scheduleExam(getApplicationContext(), newExam);

            runOnUiThread(() -> {
                Toast.makeText(this, "Exam Profile Scheduled!", Toast.LENGTH_SHORT).show();
                loadActiveExam();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}

