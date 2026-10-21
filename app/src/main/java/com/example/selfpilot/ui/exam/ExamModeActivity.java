package com.example.selfpilot.ui.exam;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.ExamProfileEntity;
import com.example.selfpilot.util.ExamScheduler;
import com.google.android.material.card.MaterialCardView;

import java.util.Calendar;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ExamModeActivity extends AppCompatActivity {

    private EditText etExamName;
    private EditText etExamDailyGoal;
    private EditText etRestrictedPackagesCsv;
    private Button btnPickStartDate;
    private Button btnPickEndDate;
    private Button btnSaveExam;

    private MaterialCardView cardActiveExam;
    private TextView tvActiveExamName;
    private TextView tvActiveExamGoal;
    private Button btnCancelActiveExam;

    private AppDatabase db;
    private ExecutorService executorService;

    private long selectedStartDate = 0;
    private long selectedEndDate = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_exam_mode);

        etExamName = findViewById(R.id.etExamName);
        etExamDailyGoal = findViewById(R.id.etExamDailyGoal);
        etRestrictedPackagesCsv = findViewById(R.id.etRestrictedPackagesCsv);
        btnPickStartDate = findViewById(R.id.btnPickStartDate);
        btnPickEndDate = findViewById(R.id.btnPickEndDate);
        btnSaveExam = findViewById(R.id.btnSaveExam);

        cardActiveExam = findViewById(R.id.cardActiveExam);
        tvActiveExamName = findViewById(R.id.tvActiveExamName);
        tvActiveExamGoal = findViewById(R.id.tvActiveExamGoal);
        btnCancelActiveExam = findViewById(R.id.btnCancelActiveExam);

        db = AppDatabase.getDatabase(getApplicationContext());
        executorService = Executors.newSingleThreadExecutor();

        loadActiveExam();

        btnPickStartDate.setOnClickListener(v -> showDatePicker(true));
        btnPickEndDate.setOnClickListener(v -> showDatePicker(false));
        btnSaveExam.setOnClickListener(v -> saveExamProfile());

        btnCancelActiveExam.setOnClickListener(v -> {
            executorService.execute(() -> {
                db.examProfileDao().deactivateAllExams();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Active Exam Mode Cancelled.", Toast.LENGTH_SHORT).show();
                    loadActiveExam();
                });
            });
        });
    }

    private void showDatePicker(boolean isStart) {
        Calendar cal = Calendar.getInstance();
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            Calendar selected = Calendar.getInstance();
            selected.set(year, month, dayOfMonth, 0, 0, 0);
            if (isStart) {
                selectedStartDate = selected.getTimeInMillis();
                btnPickStartDate.setText(year + "-" + (month + 1) + "-" + dayOfMonth);
            } else {
                selectedEndDate = selected.getTimeInMillis();
                btnPickEndDate.setText(year + "-" + (month + 1) + "-" + dayOfMonth);
            }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void loadActiveExam() {
        executorService.execute(() -> {
            // Display either the ACTIVE exam or a PENDING exam as the primary scheduled item.
            ExamProfileEntity active = null;
            if (db.examProfileDao().getActiveExams() != null && !db.examProfileDao().getActiveExams().isEmpty()) {
                active = db.examProfileDao().getActiveExams().get(0);
            } else if (db.examProfileDao().getPendingExams() != null && !db.examProfileDao().getPendingExams().isEmpty()) {
                active = db.examProfileDao().getPendingExams().get(0);
            }

            final ExamProfileEntity finalActive = active;
            runOnUiThread(() -> {
                if (finalActive != null) {
                    cardActiveExam.setVisibility(View.VISIBLE);
                    tvActiveExamName.setText(finalActive.examName + " (" + finalActive.status + ")");
                    tvActiveExamGoal.setText("Daily Goal: " + finalActive.dailyGoalMinutes + " Mins");
                } else {
                    cardActiveExam.setVisibility(View.GONE);
                }
            });
        });
    }

    private void saveExamProfile() {
        String name = etExamName.getText().toString().trim();
        String goalStr = etExamDailyGoal.getText().toString().trim();
        String restrictedCsv = etRestrictedPackagesCsv.getText().toString().trim();

        if (name.isEmpty() || selectedStartDate == 0 || selectedEndDate == 0) {
            Toast.makeText(this, "Please enter exam name and select valid dates.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (selectedEndDate <= selectedStartDate) {
            Toast.makeText(this, "End date must be strictly after start date.", Toast.LENGTH_SHORT).show();
            return;
        }

        int goalMins = 180;
        try {
            goalMins = Integer.parseInt(goalStr);
        } catch (Exception ignored) {
        }

        int finalGoalMins = goalMins;
        executorService.execute(() -> {
            ExamProfileEntity newExam = new ExamProfileEntity(
                    name,
                    selectedStartDate,
                    selectedEndDate,
                    finalGoalMins,
                    restrictedCsv,
                    ""
            );
            long newId = db.examProfileDao().insert(newExam);
            newExam.id = (int) newId;

            // Schedule the background alarms immediately
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
