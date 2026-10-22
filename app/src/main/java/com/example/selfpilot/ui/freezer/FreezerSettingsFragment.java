package com.example.selfpilot.ui.freezer;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.selfpilot.R;

public class FreezerSettingsFragment extends Fragment {

    private TextView tvSettingsAccessStatus;
    private Button btnSettingsSetup;
    private TextView tvSettingsNotificationStatus;
    private Button btnNotificationSetup;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_freezer_settings, container, false);

        tvSettingsAccessStatus = view.findViewById(R.id.tvSettingsAccessStatus);
        btnSettingsSetup = view.findViewById(R.id.btnSettingsSetup);
        tvSettingsNotificationStatus = view.findViewById(R.id.tvSettingsNotificationStatus);
        btnNotificationSetup = view.findViewById(R.id.btnNotificationSetup);

        btnSettingsSetup.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
        
        btnNotificationSetup.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
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
    }

    private boolean isNotificationListenerEnabled(Context context) {
        String listeners = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        return listeners != null && listeners.contains(context.getPackageName());
    }

    private boolean isAccessibilityServiceEnabled(Context context) {
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
