package com.example.selfpilot.ui.freezer;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.selfpilot.R;
import com.example.selfpilot.service.GuardianService;

public class FreezerSettingsFragment extends Fragment {

    private TextView tvSettingsAccessStatus;
    private Button btnSettingsSetup;
    private TextView tvSettingsNotificationStatus;
    private Button btnNotificationSetup;

    private TextView tvProtectionHealth;
    private Button btnBatteryOptimization;
    private Button btnRestartProtection;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_freezer_settings, container, false);

        tvSettingsAccessStatus = view.findViewById(R.id.tvSettingsAccessStatus);
        btnSettingsSetup = view.findViewById(R.id.btnSettingsSetup);
        tvSettingsNotificationStatus = view.findViewById(R.id.tvSettingsNotificationStatus);
        btnNotificationSetup = view.findViewById(R.id.btnNotificationSetup);

        tvProtectionHealth = view.findViewById(R.id.tvProtectionHealth);
        btnBatteryOptimization = view.findViewById(R.id.btnBatteryOptimization);
        btnRestartProtection = view.findViewById(R.id.btnRestartProtection);

        btnSettingsSetup.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        btnNotificationSetup.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        });

        btnBatteryOptimization.setOnClickListener(v -> requestBatteryExemption());

        btnRestartProtection.setOnClickListener(v -> {
            GuardianService.start(requireContext().getApplicationContext());
            Toast.makeText(requireContext(), "Protection service restarted.", Toast.LENGTH_SHORT).show();
            updateStatus();
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean isAccessibilityEnabled = isAccessibilityServiceEnabled(requireContext());

        if (isAccessibilityEnabled) {
            tvSettingsAccessStatus.setText("Accessibility Service: Enabled (Active)");
            tvSettingsAccessStatus.setTextColor(getResources().getColor(R.color.success_green, null));
            btnSettingsSetup.setVisibility(View.GONE);
        } else {
            tvSettingsAccessStatus.setText("Accessibility Service: Disabled (Required)");
            tvSettingsAccessStatus.setTextColor(getResources().getColor(R.color.error_red, null));
            btnSettingsSetup.setVisibility(View.VISIBLE);
        }

        boolean isNotificationListenerEnabled = isNotificationListenerEnabled(requireContext());
        if (isNotificationListenerEnabled) {
            tvSettingsNotificationStatus.setText("Notification Blocker: Enabled");
            tvSettingsNotificationStatus.setTextColor(getResources().getColor(R.color.success_green, null));
            btnNotificationSetup.setVisibility(View.GONE);
        } else {
            tvSettingsNotificationStatus.setText("Notification Blocker: Disabled (Tap below)");
            tvSettingsNotificationStatus.setTextColor(getResources().getColor(R.color.text_secondary, null));
            btnNotificationSetup.setVisibility(View.VISIBLE);
        }

        updateProtectionHealth(isAccessibilityEnabled);
    }

    /**
     * Surfaces why blocking could stop working in the background, so the user is not left
     * guessing: heartbeat service, accessibility connection and battery optimisation.
     */
    private void updateProtectionHealth(boolean isAccessibilityEnabled) {
        if (tvProtectionHealth == null) return;

        boolean guardianRunning = GuardianService.isRunning();
        boolean batteryRestricted = !isIgnoringBatteryOptimizations(requireContext());

        String status =
                (guardianRunning ? "Background service: Running" : "Background service: Stopped (tap Restart)")
                        + "\n" + (isAccessibilityEnabled ? "App blocking: Active" : "App blocking: Off - enable accessibility")
                        + "\n" + (batteryRestricted
                        ? "Battery optimization: Restricted (blocking can be killed)"
                        : "Battery optimization: Unrestricted");

        tvProtectionHealth.setText(status);

        boolean healthy = guardianRunning && isAccessibilityEnabled && !batteryRestricted;
        int color = healthy
                ? getResources().getColor(R.color.success_green, null)
                : getResources().getColor(R.color.error_red, null);
        tvProtectionHealth.setTextColor(color);

        if (btnBatteryOptimization != null) {
            btnBatteryOptimization.setVisibility(batteryRestricted ? View.VISIBLE : View.GONE);
        }
    }

    private void requestBatteryExemption() {
        Context context = requireContext();
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            // Some OEM skins block the direct dialog; fall back to the system list.
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(context, "Open Settings > Battery > SelfPilot and allow background activity.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean isIgnoringBatteryOptimizations(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean isNotificationListenerEnabled(Context context) {
        String listeners = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        return listeners != null && listeners.contains(context.getPackageName());
    }

    private boolean isAccessibilityServiceEnabled(Context context) {
        if (GuardianService.isAccessibilityServiceEnabled(context)) return true;

        int accessibilityEnabled = 0;
        final String service = context.getPackageName() + "/com.example.selfpilot.service.AppLockAccessibilityService";
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    context.getApplicationContext().getContentResolver(),
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException ignored) {}
        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(
                    context.getApplicationContext().getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                return settingValue.contains(service);
            }
        }
        return false;
    }
}
