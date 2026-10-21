package com.example.selfpilot.ui.exam;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.ExamProfileEntity;
import com.example.selfpilot.util.UsageUtil;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ExamModeActivity extends AppCompatActivity {

    private EditText etExamName, etExamDailyGoal;
    private Button btnSetStartDate, btnSetEndDate, btnSelectRestricted, btnSelectAllowed, btnSaveExam;
    private TextView tvExamTimeSummary, tvAppSelectionSummary;

    private AppDatabase db;
    private ExecutorService executorService;

    private long startDateMs = 0;
    private long endDateMs = 0;
    private List<String> restrictedAppPackages = new ArrayList<>();
    private List<String> allowedAppPackages = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_exam_mode);

        etExamName = findViewById(R.id.etExamName);
        etExamDailyGoal = findViewById(R.id.etExamDailyGoal);
        btnSetStartDate = findViewById(R.id.btnSetStartDate);
        btnSetEndDate = findViewById(R.id.btnSetEndDate);
        btnSelectRestricted = findViewById(R.id.btnSelectRestricted);
        btnSelectAllowed = findViewById(R.id.btnSelectAllowed);
        btnSaveExam = findViewById(R.id.btnSaveExam);
        tvExamTimeSummary = findViewById(R.id.tvExamTimeSummary);
        tvAppSelectionSummary = findViewById(R.id.tvAppSelectionSummary);

        db = AppDatabase.getDatabase(getApplicationContext());
        executorService = Executors.newSingleThreadExecutor();

        setupListeners();
    }

    private void setupListeners() {
        btnSetStartDate.setOnClickListener(v -> showDateTimePicker(true));
        btnSetEndDate.setOnClickListener(v -> showDateTimePicker(false));

        btnSelectRestricted.setOnClickListener(v -> showAppSelectionDialog(true));
        btnSelectAllowed.setOnClickListener(v -> showAppSelectionDialog(false));

        btnSaveExam.setOnClickListener(v -> saveExamProfile());
    }

    private void showDateTimePicker(boolean isStart) {
        Calendar c = Calendar.getInstance();
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            c.set(Calendar.YEAR, year);
            c.set(Calendar.MONTH, month);
            c.set(Calendar.DAY_OF_MONTH, dayOfMonth);

            new TimePickerDialog(this, (tView, hourOfDay, minute) -> {
                c.set(Calendar.HOUR_OF_DAY, hourOfDay);
                c.set(Calendar.MINUTE, minute);
                c.set(Calendar.SECOND, 0);

                if (isStart) {
                    startDateMs = c.getTimeInMillis();
                } else {
                    endDateMs = c.getTimeInMillis();
                }
                updateTimeSummary();
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show();

        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void updateTimeSummary() {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd HH:mm", Locale.getDefault());
        String start = startDateMs > 0 ? sdf.format(startDateMs) : "Not Set";
        String end = endDateMs > 0 ? sdf.format(endDateMs) : "Not Set";
        tvExamTimeSummary.setText(String.format("Start: %s | End: %s", start, end));
    }

    private void showAppSelectionDialog(boolean isBlacklist) {
        executorService.execute(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> installedApps = UsageUtil.getInstalledApps(getApplicationContext());
            String ownPackage = getPackageName();

            List<ApplicationInfo> eligibleApps = new ArrayList<>();
            for (ApplicationInfo app : installedApps) {
                if (app.packageName.equals(ownPackage)) continue;
                eligibleApps.add(app);
            }

            runOnUiThread(() -> buildSelectionDialog(pm, eligibleApps, isBlacklist));
        });
    }

    private void buildSelectionDialog(PackageManager pm, List<ApplicationInfo> eligibleApps, boolean isBlacklist) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(isBlacklist ? "Select Apps to Block" : "Select Apps to Allow");

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(30, 20, 30, 20);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout appsListLayout = new LinearLayout(this);
        appsListLayout.setOrientation(LinearLayout.VERTICAL);

        List<CheckBox> checkBoxList = new ArrayList<>();

        for (ApplicationInfo app : eligibleApps) {
            CheckBox cb = new CheckBox(this);
            cb.setText(app.loadLabel(pm).toString());
            cb.setTag(app.packageName);

            if (isBlacklist && restrictedAppPackages.contains(app.packageName)) {
                cb.setChecked(true);
            } else if (!isBlacklist && allowedAppPackages.contains(app.packageName)) {
                cb.setChecked(true);
            }

            appsListLayout.addView(cb);
            checkBoxList.add(cb);
        }

        scrollView.addView(appsListLayout);
        container.addView(scrollView);
        builder.setView(container);

        builder.setPositiveButton("Save Selection", (dialog, which) -> {
            if (isBlacklist) {
                restrictedAppPackages.clear();
            } else {
                allowedAppPackages.clear();
            }

            for (CheckBox cb : checkBoxList) {
                if (cb.isChecked()) {
                    String pkg = (String) cb.getTag();
                    if (isBlacklist) {
                        // Conflict resolution: remove from allowed if it's explicitly blocked
                        allowedAppPackages.remove(pkg);
                        restrictedAppPackages.add(pkg);
                    } else {
                        restrictedAppPackages.remove(pkg);
                        allowedAppPackages.add(pkg);
                    }
                }
            }

            tvAppSelectionSummary.setText(String.format(Locale.getDefault(), "%d Restricted, %d Allowed",
                    restrictedAppPackages.size(), allowedAppPackages.size()));
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void saveExamProfile() {
        String name = etExamName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            Toast.makeText(this, "Please enter an Exam Name.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (startDateMs == 0 || endDateMs == 0 || endDateMs <= startDateMs) {
            Toast.makeText(this, "Please select a valid future End Time that occurs after the Start Time.", Toast.LENGTH_SHORT).show();
            return;
        }

        int goalMins = 180;
        try {
            goalMins = Integer.parseInt(etExamDailyGoal.getText().toString().trim());
        } catch (Exception ignored) {}

        String restrictedCsv = TextUtils.join(",", restrictedAppPackages);
        String allowedCsv = TextUtils.join(",", allowedAppPackages);

        int finalGoalMins = goalMins;
        executorService.execute(() -> {
            ExamProfileEntity exam = new ExamProfileEntity(
                    name, startDateMs, endDateMs, finalGoalMins, restrictedCsv, allowedCsv
            );

            long id = db.examProfileDao().insert(exam);
            exam.id = (int) id;
            
            com.example.selfpilot.util.ExamScheduler.scheduleExam(getApplicationContext(), exam);

            runOnUiThread(() -> {
                Toast.makeText(this, "Exam Schedule Saved successfully.", Toast.LENGTH_SHORT).show();
                finish();
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
