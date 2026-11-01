package com.example.selfpilot.ui.permission;

import android.Manifest;
import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.selfpilot.MainActivity;
import com.example.selfpilot.R;
import com.example.selfpilot.util.UsageUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * One-time setup screen.
 *
 * Everything SelfPilot needs is requested here, in order, on the very first launch. Once the
 * flow has been completed the flag is stored, so later launches go straight to the app and the
 * user is never sent through the permission screens again (the toggles stay available in
 * Settings for manual changes).
 */
public class PermissionActivity extends AppCompatActivity {

    private static final String PREFS = "SelfPilotPrefs";
    private static final String KEY_ONBOARDED = "onboarding_completed";
    private static final int REQ_NOTIFICATIONS = 2201;

    private static final int STEP_USAGE = 0;
    private static final int STEP_ACCESSIBILITY = 1;
    private static final int STEP_NOTIFICATIONS = 2;
    private static final int STEP_BATTERY = 3;
    private static final int STEP_ALARMS = 4;

    private LinearLayout layoutSteps;
    private final List<TextView> statusViews = new ArrayList<>();

    private boolean flowActive = false;
    private int lastRequestedStep = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (isOnboarded()) {
            navigateToMain();
            return;
        }

        setContentView(R.layout.activity_permission);
        com.example.selfpilot.util.SystemBarInsets.apply(findViewById(R.id.permission_root));

        layoutSteps = findViewById(R.id.layoutPermissionSteps);
        Button btnGrantAll = findViewById(R.id.btn_grant_permission);
        TextView btnSkip = findViewById(R.id.btn_skip);

        buildStepRows();

        btnGrantAll.setOnClickListener(v -> startPermissionFlow());
        btnSkip.setOnClickListener(v -> completeOnboarding());
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (layoutSteps == null) return; // already onboarded, navigating away
        refreshStatuses();

        if (!flowActive) {
            if (allGranted()) completeOnboarding();
            return;
        }
        advanceFlow();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS && flowActive) {
            refreshStatuses();
            advanceFlow();
        }
    }

    // ------------------------------------------------------------------------------------
    // Flow
    // ------------------------------------------------------------------------------------

    private void startPermissionFlow() {
        flowActive = true;
        lastRequestedStep = -1;
        advanceFlow();
    }

    /** Opens the next missing permission screen, or finishes when nothing is left. */
    private void advanceFlow() {
        int step = nextIncompleteStep();
        if (step < 0) {
            completeOnboarding();
            return;
        }

        // Returning from a screen the user did not grant should not immediately reopen it.
        if (step == lastRequestedStep) return;

        openStep(step);
    }

    private void openStep(int step) {
        lastRequestedStep = step;
        try {
            switch (step) {
                case STEP_USAGE:
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    break;
                case STEP_ACCESSIBILITY:
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    break;
                case STEP_NOTIFICATIONS:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ActivityCompat.requestPermissions(this,
                                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                REQ_NOTIFICATIONS);
                    }
                    break;
                case STEP_BATTERY:
                    requestBatteryExemption();
                    break;
                case STEP_ALARMS:
                    requestExactAlarms();
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            Toast.makeText(this, "This screen is not available on your device.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void requestBatteryExemption() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void requestExactAlarms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + getPackageName())));
        }
    }

    private int nextIncompleteStep() {
        for (int i = 0; i < 5; i++) {
            if (!isStepGranted(i)) return i;
        }
        return -1;
    }

    private boolean allGranted() {
        return nextIncompleteStep() < 0;
    }

    // ------------------------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------------------------

    private boolean isStepGranted(int step) {
        switch (step) {
            case STEP_USAGE:
                return UsageUtil.hasUsageStatsPermission(this);
            case STEP_ACCESSIBILITY:
                return isAccessibilityEnabled();
            case STEP_NOTIFICATIONS:
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
                return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
            case STEP_BATTERY:
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            case STEP_ALARMS:
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                return am != null && am.canScheduleExactAlarms();
            default:
                return true;
        }
    }

    private boolean isAccessibilityEnabled() {
        String expected = new ComponentName(this,
                com.example.selfpilot.service.AppLockAccessibilityService.class).flattenToString();
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        for (String entry : enabled.split(":")) {
            if (entry.equalsIgnoreCase(expected)) return true;
        }
        return false;
    }

    private void buildStepRows() {
        layoutSteps.removeAllViews();
        statusViews.clear();

        addStepRow("Usage access", "Tracks screen time and app usage", R.drawable.ic_bar_chart);
        addStepRow("Accessibility service", "Blocks frozen, limited and distracting apps",
                R.drawable.ic_shield);
        addStepRow("Notifications", "Keeps the protection service alive", R.drawable.ic_bell);
        addStepRow("Battery optimisation", "Stops the system from killing protection",
                R.drawable.ic_battery);
        addStepRow("Exact alarms", "Starts and ends focus and exam sessions on time",
                R.drawable.ic_timer);

        refreshStatuses();
    }

    private void addStepRow(String title, String subtitle, int iconRes) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(8);
        row.setPadding(0, pad, 0, pad);

        ImageView icon = new ImageView(this);
        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(dp(22), dp(22));
        icon.setLayoutParams(iconParams);
        icon.setImageResource(iconRes);
        icon.setColorFilter(getColor(R.color.purple_accent_light));
        row.addView(icon);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginStart(dp(14));
        texts.setLayoutParams(textParams);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(getColor(R.color.text_primary));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        texts.addView(tvTitle);

        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText(subtitle);
        tvSubtitle.setTextColor(getColor(R.color.text_secondary));
        tvSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        texts.addView(tvSubtitle);

        row.addView(texts);

        TextView status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        status.setTypeface(null, Typeface.BOLD);
        status.setPadding(dp(10), 0, 0, 0);
        row.addView(status);
        statusViews.add(status);

        layoutSteps.addView(row);
    }

    private void refreshStatuses() {
        for (int i = 0; i < statusViews.size() && i < 5; i++) {
            boolean granted = isStepGranted(i);
            TextView status = statusViews.get(i);
            status.setText(granted ? "GRANTED" : "REQUIRED");
            status.setTextColor(getColor(granted ? R.color.success_green : R.color.warning_yellow));
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    // ------------------------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------------------------

    private boolean isOnboarded() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.getBoolean(KEY_ONBOARDED, false);
    }

    private void completeOnboarding() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ONBOARDED, true)
                .apply();
        navigateToMain();
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}
