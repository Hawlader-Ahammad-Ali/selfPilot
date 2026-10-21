package com.example.selfpilot.ui.apps;

import android.app.usage.UsageStats;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.AppInfoDao;
import com.example.selfpilot.data.AppInfoEntity;
import com.example.selfpilot.util.UsageUtil;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppsFragment extends Fragment {

    private AppListAdapter adapter;
    private AppDatabase db;
    private ExecutorService executorService;
    private EditText etSearchApp;
    private ChipGroup chipGroupFilter;

    private List<AppInfoEntity> masterAppList = new ArrayList<>();
    private Map<String, UsageStats> todayUsageStats = new HashMap<>();
    private String currentSearchQuery = "";
    private int currentFilterId = R.id.chipAll;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_apps, container, false);
        RecyclerView recyclerView = view.findViewById(R.id.recycler_view_apps);
        etSearchApp = view.findViewById(R.id.etSearchApp);
        chipGroupFilter = view.findViewById(R.id.chipGroupFilter);

        db = AppDatabase.getDatabase(requireContext());
        executorService = Executors.newSingleThreadExecutor();

        adapter = new AppListAdapter(requireContext().getPackageManager(), db);
        recyclerView.setAdapter(adapter);

        setupListeners();
        loadApps();

        return view;
    }

    private void setupListeners() {
        if (etSearchApp != null) {
            etSearchApp.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    currentSearchQuery = s != null ? s.toString().trim().toLowerCase() : "";
                    applyFilters();
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }

        if (chipGroupFilter != null) {
            chipGroupFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
                if (!checkedIds.isEmpty()) {
                    currentFilterId = checkedIds.get(0);
                } else {
                    currentFilterId = R.id.chipAll;
                }
                applyFilters();
            });
        }
    }

    private void loadApps() {
        executorService.execute(() -> {
            PackageManager pm = requireContext().getPackageManager();
            List<ApplicationInfo> installedApps = UsageUtil.getInstalledApps(requireContext());
            AppInfoDao dao = db.appInfoDao();

            String ownPackage = requireContext().getPackageName();

            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            long startOfDay = calendar.getTimeInMillis();
            
            todayUsageStats = UsageUtil.getUsageStats(requireContext(), startOfDay, System.currentTimeMillis());
            if (todayUsageStats == null) {
                todayUsageStats = new HashMap<>();
            }

            for (ApplicationInfo appInfo : installedApps) {
                // Exclude SelfPilot itself
                if (appInfo.packageName.equals(ownPackage)) continue;

                String packageName = appInfo.packageName;
                String appName = appInfo.loadLabel(pm).toString();

                AppInfoEntity existing = dao.getAppInfo(packageName);
                if (existing == null) {
                    dao.insert(new AppInfoEntity(packageName, appName, false));
                }
            }

            masterAppList = dao.getAllApps();

            if (getActivity() != null) {
                getActivity().runOnUiThread(this::applyFilters);
            }
        });
    }

    private void applyFilters() {
        List<AppInfoEntity> filtered = new ArrayList<>();

        for (AppInfoEntity app : masterAppList) {
            // Check Search
            if (!currentSearchQuery.isEmpty()) {
                if (!app.appName.toLowerCase().contains(currentSearchQuery) &&
                        !app.packageName.toLowerCase().contains(currentSearchQuery)) {
                    continue;
                }
            }

            // Check Category Filter
            if (currentFilterId == R.id.chipStudy) {
                if (!app.isStudyApp) continue;
            } else if (currentFilterId == R.id.chipNonStudy) {
                if (app.isStudyApp) continue;
            } else if (currentFilterId == R.id.chipFrozen) {
                if (!app.isFrozen) continue;
            }

            filtered.add(app);
        }

        adapter.setAppList(filtered, todayUsageStats);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}
