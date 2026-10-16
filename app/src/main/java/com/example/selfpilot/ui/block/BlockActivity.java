package com.example.selfpilot.ui.block;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.EmergencyUnlockEntity;
import com.example.selfpilot.data.RestrictionRuleEntity;

import java.util.Calendar;

public class BlockActivity extends AppCompatActivity {

    private String targetPackageName;
    private AppDatabase db;
    private Button btnEmergencyUnlock;
    private EmergencyUnlockEntity todayUnlockEntity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_block);

        targetPackageName = getIntent().getStringExtra("packageName");
        db = AppDatabase.getDatabase(getApplicationContext());

        Button btnGoHome = findViewById(R.id.btn_go_home);
        btnGoHome.setOnClickListener(v -> {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(homeIntent);
            finish();
        });

        btnEmergencyUnlock = findViewById(R.id.btn_emergency_unlock);
        
        loadEmergencyUnlockState();

        btnEmergencyUnlock.setOnClickListener(v -> {
            performEmergencyUnlock();
        });
    }

    private void loadEmergencyUnlockState() {
        new Thread(() -> {
            long todayStart = getTodayStart();
            todayUnlockEntity = db.emergencyUnlockDao().getUnlockByDate(todayStart);
            if (todayUnlockEntity == null) {
                todayUnlockEntity = new EmergencyUnlockEntity();
                todayUnlockEntity.date = todayStart;
                todayUnlockEntity.unlocksUsed = 0;
                db.emergencyUnlockDao().insert(todayUnlockEntity);
                todayUnlockEntity = db.emergencyUnlockDao().getUnlockByDate(todayStart);
            }

            if (todayUnlockEntity.unlocksUsed < 3) {
                runOnUiThread(() -> {
                    btnEmergencyUnlock.setEnabled(true);
                    btnEmergencyUnlock.setText("Emergency Unlock (" + (3 - todayUnlockEntity.unlocksUsed) + " left)");
                });
            } else {
                runOnUiThread(() -> {
                    btnEmergencyUnlock.setEnabled(false);
                    btnEmergencyUnlock.setText("No Unlocks Left Today");
                });
            }
        }).start();
    }

    private void performEmergencyUnlock() {
        if (targetPackageName == null || todayUnlockEntity == null) return;
        
        new Thread(() -> {
            todayUnlockEntity.unlocksUsed++;
            db.emergencyUnlockDao().update(todayUnlockEntity);

            RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(targetPackageName);
            if (rule != null) {
                rule.unlockedUntil = System.currentTimeMillis() + (15 * 60 * 1000); // 15 minutes
                db.restrictionRuleDao().update(rule);
            }
            
            runOnUiThread(() -> finish());
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
