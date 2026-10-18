package com.example.selfpilot.ui.freezer;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
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

        db = AppDatabase.getDatabase(getApplicationContext());
        executorService = Executors.newSingleThreadExecutor();

        adapter = new FrozenAppAdapter(getPackageManager(), db, this::loadFrozenApps);
        rvFrozenApps.setAdapter(adapter);

        setupListeners();
        loadFrozenApps();
    }

    private void setupListeners() {
        btnAddAppsToFreezer.setOnClickListener(v -> showAddAppsDialog());

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
                .setMessage("Freeze " + selectedAppInfos.size() + " selected application(s)? Launch access will be strictly restricted until unfrozen.")
                .setPositiveButton("Freeze Now", (dialog, which) -> {
                    executorService.execute(() -> {
                        long now = System.currentTimeMillis();
                        for (ApplicationInfo info : selectedAppInfos) {
                            String pkg = info.packageName;
                            String name = info.loadLabel(pm).toString();

                            // 1. Insert/Update FrozenAppEntity
                            FrozenAppEntity frozen = new FrozenAppEntity(pkg, name, true, now);
                            db.frozenAppDao().insert(frozen);

                            // 2. Update AppInfoEntity
                            AppInfoEntity appInfo = db.appInfoDao().getAppInfo(pkg);
                            if (appInfo == null) {
                                appInfo = new AppInfoEntity(pkg, name, false);
                            }
                            appInfo.isFrozen = true;
                            appInfo.isBlocked = true;
                            db.appInfoDao().update(appInfo);

                            // 3. Update RestrictionRuleEntity
                            RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(pkg);
                            if (rule == null) {
                                rule = new RestrictionRuleEntity();
                                rule.packageName = pkg;
                            }
                            rule.isBlocked = true;
                            db.restrictionRuleDao().insert(rule);
                        }

                        runOnUiThread(() -> {
                            Toast.makeText(this, selectedAppInfos.size() + " app(s) added to Freezer.", Toast.LENGTH_SHORT).show();
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
