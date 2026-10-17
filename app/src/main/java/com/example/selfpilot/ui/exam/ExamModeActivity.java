package com.example.selfpilot.ui.exam;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.ExamProfileEntity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ExamModeActivity extends AppCompatActivity {

    private EditText etExamName;
    private EditText etExamDailyGoal;
    private Button btnSaveExam;
    private AppDatabase db;
    private ExecutorService executorService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_exam_mode);

        etExamName = findViewById(R.id.etExamName);
        etExamDailyGoal = findViewById(R.id.etExamDailyGoal);
        btnSaveExam = findViewById(R.id.btnSaveExam);

        db = AppDatabase.getDatabase(getApplicationContext());
        executorService = Executors.newSingleThreadExecutor();

        loadActiveExam();

        btnSaveExam.setOnClickListener(v -> saveExamProfile());
    }

    private void loadActiveExam() {
        executorService.execute(() -> {
            ExamProfileEntity active = db.examProfileDao().getActiveExam();
            if (active != null) {
                runOnUiThread(() -> {
                    etExamName.setText(active.examName);
                    etExamDailyGoal.setText(String.valueOf(active.dailyGoalMinutes));
                });
            }
        });
    }

    private void saveExamProfile() {
        String name = etExamName.getText().toString().trim();
        String goalStr = etExamDailyGoal.getText().toString().trim();

        if (name.isEmpty()) {
            Toast.makeText(this, "Please enter exam name", Toast.LENGTH_SHORT).show();
            return;
        }

        int goalMins = 180;
        try {
            goalMins = Integer.parseInt(goalStr);
        } catch (Exception ignored) {
        }

        int finalGoalMins = goalMins;
        executorService.execute(() -> {
            db.examProfileDao().deactivateAllExams();
            ExamProfileEntity newExam = new ExamProfileEntity(
                    name,
                    System.currentTimeMillis(),
                    System.currentTimeMillis() + (14 * 86400000L),
                    finalGoalMins,
                    true
            );
            db.examProfileDao().insert(newExam);

            runOnUiThread(() -> {
                Toast.makeText(this, "Exam Profile Activated!", Toast.LENGTH_SHORT).show();
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
