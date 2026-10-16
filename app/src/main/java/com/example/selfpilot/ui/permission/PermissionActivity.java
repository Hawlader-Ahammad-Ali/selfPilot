package com.example.selfpilot.ui.permission;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import com.example.selfpilot.MainActivity;
import com.example.selfpilot.R;
import com.example.selfpilot.util.UsageUtil;

public class PermissionActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (UsageUtil.hasUsageStatsPermission(this)) {
            navigateToMain();
            return;
        }
        
        setContentView(R.layout.activity_permission);
        
        Button btnGrantPermission = findViewById(R.id.btn_grant_permission);
        btnGrantPermission.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (UsageUtil.hasUsageStatsPermission(this)) {
            navigateToMain();
        }
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}
