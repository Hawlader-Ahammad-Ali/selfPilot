package com.example.selfpilot.ui.freezer;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.AppInfoEntity;
import com.example.selfpilot.data.FrozenAppEntity;
import com.example.selfpilot.data.RestrictionRuleEntity;
import com.example.selfpilot.util.ShizukuUtil;
import com.example.selfpilot.util.UsageUtil;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppFreezerActivity extends AppCompatActivity {

    private TextView tvFrozenAppsCountBadge;
    private EditText etSearchFreezer;
    private ProgressBar progressLoadingFreezer;
    private LinearLayout layoutEmptyFreezer;
    private RecyclerView rvFrozenApps;
    private ExtendedFloatingActionButton btnAddAppsToFreezer;
    private Button btnShizukuStatus;

    private AppDatabase db;
    private FrozenAppAdapter adapter;
    private ExecutorService executorService;

    private List<FrozenAppEntity> masterFrozenList = new ArrayList<>();
    private String currentSearchQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_freezer);

        tvFrozenAppsCountBadge = findViewById(R.id.tvFrozenAppsCountBadge);
        etSearchFreezer = findViewById(R.id.etSearchFreezer);
        progressLoadingFreezer = findViewById(R.id.progressLoadingFreezer);
        layoutEmptyFreezer = findViewById(R.id.layoutEmptyFreezer);
        rvFrozenApps = findViewById(R.id.rvFrozenApps);
        btnAddAppsToFreezer = findViewById(R.id.btnAddAppsToFreezer);
        btnShizukuStatus = findViewById(R.id.btnShizukuStatus);

        db = AppDatabase.getDatabase(getApplicationContext());
        executorService = Executors.newSingleThreadExecutor();

        adapter = new FrozenAppAdapter(getPackageManager(), db, this::loadFrozenApps);
        rvFrozenApps.setAdapter(adapter);

        setupListeners();
        loadFrozenApps();
        showStandardModeDisclosure();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateShizukuStatus();
    }

    private void updateShizukuStatus() {
        if (btnShizukuStatus == null) return;
        
        if (ShizukuUtil.hasShizukuPermission()) {
            btnShizukuStatus.setText("Advanced Mode: ACTIVE (True Freeze)");
            btnShizukuStatus.setTextColor(getResources().getColor(R.color.success_green, null));
        } else {
            btnShizukuStatus.setText("Advanced Mode: OFF (Tap to Setup Shizuku)");
            btnShizukuStatus.setTextColor(getResources().getColor(R.color.warning_yellow, null));
        }
    }

    private void showStandardModeDisclosure() {
        boolean hasSeenDisclosure = getSharedPreferences("SelfPilotPrefs", MODE_PRIVATE)
                .getBoolean("has_seen_freezer_disclosure", false);
        
        if (!hasSeenDisclosure) {
            new AlertDialog.Builder(this)
                    .setTitle("App Freezer Mode")
                    .setMessage("SelfPilot defaults to Standard Mode, using Accessibility Services to strictly restrict app launching when frozen.\n\nTo achieve true OEM-style freezing (killing background RAM, hiding launcher icons, stopping notifications), you can enable Advanced Mode by tapping the banner at the top and granting Shizuku access.")
                    .setPositiveButton("I Understand", (dialog, which) -> {
                        getSharedPreferences("SelfPilotPrefs", MODE_PRIVATE)
                                .edit()
                                .putBoolean("has_seen_freezer_disclosure", true)
                                .apply();
                    })
                    .setCancelable(false)
                    .show();
        }
    }

    private void setupListeners() {
        btnAddAppsToFreezer.setOnClickListener(v -> showAddAppsDialog());

        if (btnShizukuStatus != null) {
            btnShizukuStatus.setOnClickListener(v -> {
                if (ShizukuUtil.isShizukuAvailable()) {
                    if (!ShizukuUtil.hasShizukuPermission()) {
                        ShizukuUtil.requestShizukuPermission(1001);
                    } else {
                        Toast.makeText(this, "Shizuku Advanced Mode is already active.", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("Shizuku Not Found")
                            .setMessage("Shizuku is not running or not installed. Advanced freezing (icon hiding, process killing) requires Shizuku to bridge system APIs without root. SelfPilot will continue to use Standard Mode.")
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        }

        if (etSearchFreezer != null) {
            etSearchFreezer.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    currentSearchQuery = s != null ? s.toString().trim().toLowerCase() : "";
                    applyFilter();
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }
    }

    private void loadFrozenApps() {
        runOnUiThread(() -> progressLoadingFreezer.setVisibility(View.VISIBLE));

        executorService.execute(() -> {
            masterFrozenList = db.frozenAppDao().getFrozenApps();

            runOnUiThread(() -> {
                progressLoadingFreezer.setVisibility(View.GONE);
                applyFilter();
            });
        });
    }

    private void applyFilter() {
        List<FrozenAppEntity> filtered = new ArrayList<>();
        for (FrozenAppEntity app : masterFrozenList) {
            if (!currentSearchQuery.isEmpty()) {
                if (!app.appName.toLowerCase().contains(currentSearchQuery) &&
                        !app.packageName.toLowerCase().contains(currentSearchQuery)) {
                    continue;
                }
            }
            filtered.add(app);
        }

        adapter.setFrozenList(filtered);

        int totalCount = masterFrozenList.size();
        tvFrozenAppsCountBadge.setText(totalCount + " Frozen");

        if (filtered.isEmpty()) {
            layoutEmptyFreezer.setVisibility(View.VISIBLE);
            rvFrozenApps.setVisibility(View.GONE);
        } else {
            layoutEmptyFreezer.setVisibility(View.GONE);
            rvFrozenApps.setVisibility(View.VISIBLE);
        }
    }

    private void showAddAppsDialog() {
        progressLoadingFreezer.setVisibility(View.VISIBLE);

        executorService.execute(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> installedApps = UsageUtil.getInstalledApps(getApplicationContext());
            String ownPackage = getPackageName();

            List<ApplicationInfo> eligibleApps = new ArrayList<>();
            for (ApplicationInfo app : installedApps) {
                if (app.packageName.equals(ownPackage)) continue;
                eligibleApps.add(app);
            }

            runOnUiThread(() -> {
                progressLoadingFreezer.setVisibility(View.GONE);
                buildAddAppsSelectionDialog(pm, eligibleApps);
            });
        });
    }

    private void buildAddAppsSelectionDialog(PackageManager pm, List<ApplicationInfo> eligibleApps) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add Apps to Freezer");

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(30, 20, 30, 20);

        CheckBox cbSelectAll = new CheckBox(this);
        cbSelectAll.setText("Select All Apps");
        container.addView(cbSelectAll);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout appsListLayout = new LinearLayout(this);
        appsListLayout.setOrientation(LinearLayout.VERTICAL);

        List<CheckBox> checkBoxList = new ArrayList<>();
        List<ApplicationInfo> selectedAppInfos = new ArrayList<>();

        for (ApplicationInfo app : eligibleApps) {
            String label = app.loadLabel(pm).toString();
            CheckBox cb = new CheckBox(this);
            cb.setText(label + " (" + app.packageName + ")");
            cb.setTag(app);
            appsListLayout.addView(cb);
            checkBoxList.add(cb);
        }

        cbSelectAll.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (CheckBox cb : checkBoxList) {
                cb.setChecked(isChecked);
            }
        });

        scrollView.addView(appsListLayout);
        container.addView(scrollView);
        builder.setView(container);

        builder.setPositiveButton("Freeze Selected", (dialog, which) -> {
            for (CheckBox cb : checkBoxList) {
                if (cb.isChecked()) {
                    ApplicationInfo info = (ApplicationInfo) cb.getTag();
                    if (info != null) {
                        selectedAppInfos.add(info);
                    }
                }
            }

            if (selectedAppInfos.isEmpty()) {
                Toast.makeText(this, "No apps selected.", Toast.LENGTH_SHORT).show();
                return;
            }

            confirmFreezeSelectedApps(pm, selectedAppInfos);
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void confirmFreezeSelectedApps(PackageManager pm, List<ApplicationInfo> selectedAppInfos) {
        new AlertDialog.Builder(this)
                .setTitle("Confirm App Freeze")
                .setMessage("Freeze " + selectedAppInfos.size() + " selected application(s)?")
                .setPositiveButton("Freeze Now", (dialog, which) -> {
                    executorService.execute(() -> {
                        long now = System.currentTimeMillis();
                        boolean useShizuku = ShizukuUtil.hasShizukuPermission();
                        
                        for (ApplicationInfo info : selectedAppInfos) {
                            String pkg = info.packageName;
                            String name = info.loadLabel(pm).toString();

                            if (useShizuku) {
                                boolean success = ShizukuUtil.freezeApp(pkg);
                                if (success) {
                                    FrozenAppEntity frozen = new FrozenAppEntity(pkg, name, true, now);
                                    frozen.restrictionMode = "SHIZUKU";
                                    db.frozenAppDao().insert(frozen);
                                }
                            } else {
                                // Standard Mode
                                FrozenAppEntity frozen = new FrozenAppEntity(pkg, name, true, now);
                                frozen.restrictionMode = "STANDARD";
                                db.frozenAppDao().insert(frozen);

                                AppInfoEntity appInfo = db.appInfoDao().getAppInfo(pkg);
                                if (appInfo == null) {
                                    appInfo = new AppInfoEntity(pkg, name, false);
                                }
                                appInfo.isFrozen = true;
                                appInfo.isBlocked = true;
                                db.appInfoDao().update(appInfo);

                                RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(pkg);
                                if (rule == null) {
                                    rule = new RestrictionRuleEntity();
                                    rule.packageName = pkg;
                                }
                                rule.isBlocked = true;
                                db.restrictionRuleDao().insert(rule);
                            }
                        }

                        runOnUiThread(() -> {
                            Toast.makeText(this, selectedAppInfos.size() + " app(s) processed.", Toast.LENGTH_SHORT).show();
                            loadFrozenApps();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}
