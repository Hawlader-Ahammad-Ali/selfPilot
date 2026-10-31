package com.example.selfpilot.ui.freezer;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.FrozenAppEntity;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FreezerDashboardFragment extends Fragment {

    private TextView tvFreezerStatus;
    private TextView tvFreezerCount;
    private TextView tvAccessibilityStatus;
    private LinearLayout layoutAppPreview;
    private TextView tvPreviewEmpty;
    private Button btnManageAppsShortcut;
    private Button btnSettingsShortcut;

    private ExecutorService executorService;
    private AppDatabase db;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_freezer_dashboard, container, false);

        tvFreezerStatus = view.findViewById(R.id.tvFreezerStatus);
        tvFreezerCount = view.findViewById(R.id.tvFreezerCount);
        tvAccessibilityStatus = view.findViewById(R.id.tvAccessibilityStatus);
        layoutAppPreview = view.findViewById(R.id.layoutAppPreview);
        tvPreviewEmpty = view.findViewById(R.id.tvPreviewEmpty);
        btnManageAppsShortcut = view.findViewById(R.id.btnManageAppsShortcut);
        btnSettingsShortcut = view.findViewById(R.id.btnSettingsShortcut);

        db = AppDatabase.getDatabase(requireContext());
        executorService = Executors.newSingleThreadExecutor();

        btnManageAppsShortcut.setOnClickListener(v -> {
            if (getActivity() instanceof FreezerMainActivity) {
                ((FreezerMainActivity) getActivity()).getSupportFragmentManager().beginTransaction()
                        .replace(R.id.freezer_nav_host_fragment, new FreezerManageAppsFragment())
                        .addToBackStack(null)
                        .commit();
            }
        });
        
        btnSettingsShortcut.setOnClickListener(v -> {
            if (getActivity() instanceof FreezerMainActivity) {
                ((FreezerMainActivity) getActivity()).getSupportFragmentManager().beginTransaction()
                        .replace(R.id.freezer_nav_host_fragment, new FreezerSettingsFragment())
                        .addToBackStack(null)
                        .commit();
            }
        });

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateDashboard();
    }

    private void updateDashboard() {
        boolean isAccessibilityEnabled = isAccessibilityServiceEnabled(requireContext());

        if (isAccessibilityEnabled) {
            tvAccessibilityStatus.setText("Accessibility Service: Enabled");
            tvAccessibilityStatus.setTextColor(getResources().getColor(R.color.success_green, null));
            tvFreezerStatus.setText("Status: Active");
            tvFreezerStatus.setTextColor(getResources().getColor(R.color.success_green, null));
        } else {
            tvAccessibilityStatus.setText("Accessibility Service: Disabled");
            tvAccessibilityStatus.setTextColor(getResources().getColor(R.color.error_red, null));
            tvFreezerStatus.setText("Status: Service Disabled");
            tvFreezerStatus.setTextColor(getResources().getColor(R.color.error_red, null));
        }

        executorService.execute(() -> {
            List<FrozenAppEntity> rawApps = db.frozenAppDao().getFrozenApps();
            String selfPkg = requireContext().getPackageName();
            List<FrozenAppEntity> frozenApps = new ArrayList<>();
            if (rawApps != null) {
                for (FrozenAppEntity entity : rawApps) {
                    if (entity.isFrozen && !entity.packageName.equals("com.example.selfpilot") && !entity.packageName.equals(selfPkg)) {
                        frozenApps.add(entity);
                    }
                }
            }
            PackageManager pm = requireContext().getPackageManager();

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (frozenApps == null || frozenApps.isEmpty()) {
                        tvFreezerCount.setText("0 Apps Frozen");
                        tvPreviewEmpty.setVisibility(View.VISIBLE);
                        layoutAppPreview.removeAllViews();
                    } else {
                        tvFreezerCount.setText(frozenApps.size() + " Apps Frozen");
                        tvPreviewEmpty.setVisibility(View.GONE);
                        layoutAppPreview.removeAllViews();

                        for (int i = 0; i < Math.min(frozenApps.size(), 10); i++) {
                            FrozenAppEntity app = frozenApps.get(i);
                            ImageView iconView = new ImageView(requireContext());
                            int size = (int) (48 * getResources().getDisplayMetrics().density);
                            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
                            params.setMargins(0, 0, 16, 0);
                            iconView.setLayoutParams(params);

                            try {
                                Drawable icon = pm.getApplicationIcon(app.packageName);
                                iconView.setImageDrawable(icon);
                            } catch (Exception e) {
                                iconView.setImageResource(android.R.drawable.sym_def_app_icon);
                            }
                            layoutAppPreview.addView(iconView);
                        }
                    }
                });
            }
        });
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}
