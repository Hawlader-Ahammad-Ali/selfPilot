package com.example.selfpilot.ui.block;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.EmergencyUnlockEntity;
import com.example.selfpilot.data.ExamProfileEntity;
import com.example.selfpilot.data.FocusSessionEntity;
import com.example.selfpilot.data.FrozenAppEntity;
import com.example.selfpilot.data.RestrictionRuleEntity;

import java.util.Calendar;
import java.util.List;

public class BlockActivity extends AppCompatActivity {

    private String targetPackageName;
    private AppDatabase db;
    private Button btnEmergencyUnlock;
    private Button btnForceStop;
    private TextView tvBlockedMessage;
    private EmergencyUnlockEntity todayUnlockEntity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_block);

        targetPackageName = getIntent().getStringExtra("packageName");
        db = AppDatabase.getDatabase(getApplicationContext());

        Button btnGoHome = findViewById(R.id.btn_go_home);
        btnGoHome.setOnClickListener(v -> goHome());

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                goHome();
            }
        });

        btnEmergencyUnlock = findViewById(R.id.btn_emergency_unlock);
        btnForceStop = findViewById(R.id.btn_force_stop);
        tvBlockedMessage = findViewById(R.id.tvBlockedMessage);

        loadStateAndDetermineMode();

        btnEmergencyUnlock.setOnClickListener(v -> performEmergencyUnlock());
        
        btnForceStop.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + targetPackageName));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });
    }

    private void goHome() {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);
        finish();
    }

    private void loadStateAndDetermineMode() {
        new Thread(() -> {
            boolean isFrozen = false;
            boolean isFocusMode = false;
            boolean isExamMode = false;
            
            if (targetPackageName != null) {
                FrozenAppEntity frozenApp = db.frozenAppDao().getFrozenApp(targetPackageName);
                if (frozenApp != null && frozenApp.isFrozen) {
                    isFrozen = true;
                }
            }

            if (!isFrozen) {
                List<ExamProfileEntity> activeExams = db.examProfileDao().getActiveExams();
                if (activeExams != null && !activeExams.isEmpty()) {
                    for (ExamProfileEntity exam : activeExams) {
                        if (exam.restrictedAppsCsv != null && exam.restrictedAppsCsv.contains(targetPackageName)) {
                            isExamMode = true;
                            break;
                        }
                    }
                }
            }

            FocusSessionEntity activeSession = db.focusSessionDao().getActiveSession();
            if (activeSession != null && !isFrozen && !isExamMode) {
                isFocusMode = true;
            }

            final boolean finalIsFrozen = isFrozen;
            final boolean finalIsFocusMode = isFocusMode;
            final boolean finalIsExamMode = isExamMode;

            long todayStart = getTodayStart();
            todayUnlockEntity = db.emergencyUnlockDao().getUnlockByDate(todayStart);
            if (todayUnlockEntity == null) {
                todayUnlockEntity = new EmergencyUnlockEntity();
                todayUnlockEntity.date = todayStart;
                todayUnlockEntity.unlocksUsed = 0;
                db.emergencyUnlockDao().insert(todayUnlockEntity);
                todayUnlockEntity = db.emergencyUnlockDao().getUnlockByDate(todayStart);
            }

            runOnUiThread(() -> {
                if (finalIsFrozen) {
                    tvBlockedMessage.setText("This application is currently FROZEN.");
                    btnForceStop.setVisibility(View.VISIBLE);
                    btnEmergencyUnlock.setVisibility(View.GONE);
                } else if (finalIsExamMode) {
                    tvBlockedMessage.setText("Exam Mode Active.\nThis app is restricted to help you study.");
                    btnForceStop.setVisibility(View.GONE);
                } else if (finalIsFocusMode) {
                    tvBlockedMessage.setText("Focus Mode Active. This app is restricted.");
                    btnForceStop.setVisibility(View.GONE);
                } else {
                    tvBlockedMessage.setText("This app is restricted by SelfPilot.");
                    btnForceStop.setVisibility(View.GONE);
                }

                if (!finalIsFrozen) {
                    btnEmergencyUnlock.setVisibility(View.VISIBLE);
                    if (todayUnlockEntity.unlocksUsed < 3) {
                        btnEmergencyUnlock.setEnabled(true);
                        btnEmergencyUnlock.setText("Emergency Unlock (" + (3 - todayUnlockEntity.unlocksUsed) + " left)");
                    } else {
                        btnEmergencyUnlock.setEnabled(false);
                        btnEmergencyUnlock.setText("No Unlocks Left Today");
                    }
                }
            });
        }).start();
    }

    private void performEmergencyUnlock() {
        if (targetPackageName == null || todayUnlockEntity == null) return;
        
        new Thread(() -> {
            // 1. Increment daily emergency unlock
            todayUnlockEntity.unlocksUsed++;
            db.emergencyUnlockDao().update(todayUnlockEntity);

            // 2. Set generic rule unlocked until now + 5 minutes
            RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(targetPackageName);
            if (rule == null) {
                rule = new RestrictionRuleEntity();
                rule.packageName = targetPackageName;
                db.restrictionRuleDao().insert(rule);
                rule = db.restrictionRuleDao().getRuleByPackageName(targetPackageName);
            }
            
            if (rule != null) {
                rule.unlockedUntil = System.currentTimeMillis() + (5 * 60 * 1000); // 5 minutes
                db.restrictionRuleDao().update(rule);
            }

            // 3. If Focus Mode is active, deduct XP
            FocusSessionEntity activeSession = db.focusSessionDao().getActiveSession();
            if (activeSession != null) {
                activeSession.emergencyUnlocksUsed++;
                db.focusSessionDao().update(activeSession);
            }

            // 4. If Exam Mode is active, deduct XP for active exams
            List<ExamProfileEntity> activeExams = db.examProfileDao().getActiveExams();
            if (activeExams != null) {
                for (ExamProfileEntity exam : activeExams) {
                    exam.emergencyUnlocksUsed++;
                    db.examProfileDao().update(exam);
                }
            }
            
            runOnUiThread(this::goHome);
        }).start();
    }

    private long getTodayStart() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
