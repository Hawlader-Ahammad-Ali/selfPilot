package com.example.selfpilot.ui.freezer;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
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

public class InstalledAppsTabFragment extends Fragment {

    private EditText etSearchInstalled;
    private Button btnSelectAllInstalled;
    private Button btnClearAllInstalled;
    private ProgressBar pbLoadingInstalled;
    private RecyclerView rvInstalledApps;
    private ExtendedFloatingActionButton btnFreezeSelected;

    private AppAdapter adapter;
    private ExecutorService executorService;
    private AppDatabase db;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_freezer_installed_apps, container, false);

        etSearchInstalled = view.findViewById(R.id.etSearchInstalled);
        btnSelectAllInstalled = view.findViewById(R.id.btnSelectAllInstalled);
        btnClearAllInstalled = view.findViewById(R.id.btnClearAllInstalled);
        pbLoadingInstalled = view.findViewById(R.id.pbLoadingInstalled);
        rvInstalledApps = view.findViewById(R.id.rvInstalledApps);
        btnFreezeSelected = view.findViewById(R.id.btnFreezeSelected);

        db = AppDatabase.getDatabase(requireContext());
        executorService = Executors.newSingleThreadExecutor();

        rvInstalledApps.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new AppAdapter(() -> {
            int selectedCount = adapter.getSelectedCount();
            btnFreezeSelected.setText("Freeze " + selectedCount + " Apps");
            btnFreezeSelected.setEnabled(selectedCount > 0);
        });
        rvInstalledApps.setAdapter(adapter);

        setupListeners();
        loadApps();

        return view;
    }

    private void setupListeners() {
        etSearchInstalled.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnSelectAllInstalled.setOnClickListener(v -> adapter.selectAll());
        btnClearAllInstalled.setOnClickListener(v -> adapter.clearAll());

        btnFreezeSelected.setOnClickListener(v -> {
            List<AppItem> selected = adapter.getSelectedItems();
            if (selected.isEmpty()) return;

            new AlertDialog.Builder(requireContext())
                    .setTitle("Confirm App Freeze")
                    .setMessage("Freeze " + selected.size() + " selected application(s)? Launch access will be strictly restricted until unfrozen.")
                    .setPositiveButton("Freeze Now", (dialog, which) -> {
                        freezeApps(selected);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private void loadApps() {
        pbLoadingInstalled.setVisibility(View.VISIBLE);
        rvInstalledApps.setVisibility(View.GONE);

        executorService.execute(() -> {
            PackageManager pm = requireContext().getPackageManager();
            List<ApplicationInfo> installedApps = UsageUtil.getInstalledApps(requireContext());
            String ownPackage = requireContext().getPackageName();
            
            // Query currently frozen to exclude from installed tab
            List<FrozenAppEntity> frozenEntities = db.frozenAppDao().getFrozenApps();
            List<String> frozenPackageNames = new ArrayList<>();
            if (frozenEntities != null) {
                for (FrozenAppEntity f : frozenEntities) {
                    frozenPackageNames.add(f.packageName);
                }
            }

            List<AppItem> items = new ArrayList<>();
            for (ApplicationInfo app : installedApps) {
                if (app.packageName.equals(ownPackage)) continue;
                if (frozenPackageNames.contains(app.packageName)) continue; // Already frozen

                String name = app.loadLabel(pm).toString();
                Drawable icon = null;
                try {
                    icon = pm.getApplicationIcon(app.packageName);
                } catch (Exception ignored) {}

                items.add(new AppItem(app.packageName, name, icon));
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    pbLoadingInstalled.setVisibility(View.GONE);
                    rvInstalledApps.setVisibility(View.VISIBLE);
                    adapter.setItems(items);
                });
            }
        });
    }

    private void freezeApps(List<AppItem> appsToFreeze) {
        executorService.execute(() -> {
            long now = System.currentTimeMillis();
            boolean useShizuku = ShizukuUtil.hasShizukuPermission();

            for (AppItem item : appsToFreeze) {
                String pkg = item.packageName;
                String name = item.appName;

                if (useShizuku) {
                    boolean success = ShizukuUtil.freezeApp(pkg);
                    if (success) {
                        FrozenAppEntity frozen = new FrozenAppEntity(pkg, name, true, now);
                        frozen.restrictionMode = "SHIZUKU";
                        db.frozenAppDao().insert(frozen);
                    }
                } else {
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

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(), appsToFreeze.size() + " app(s) frozen.", Toast.LENGTH_SHORT).show();
                    loadApps(); // reload list to remove frozen apps
                });
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    static class AppItem {
        String packageName;
        String appName;
        Drawable icon;
        boolean isSelected = false;

        public AppItem(String packageName, String appName, Drawable icon) {
            this.packageName = packageName;
            this.appName = appName;
            this.icon = icon;
        }
    }

    class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {

        private List<AppItem> allItems = new ArrayList<>();
        private List<AppItem> filteredItems = new ArrayList<>();
        private Runnable selectionCallback;

        AppAdapter(Runnable selectionCallback) {
            this.selectionCallback = selectionCallback;
        }

        void setItems(List<AppItem> items) {
            allItems = new ArrayList<>(items);
            filteredItems = new ArrayList<>(items);
            notifyDataSetChanged();
            selectionCallback.run();
        }

        void filter(String query) {
            filteredItems.clear();
            if (query.isEmpty()) {
                filteredItems.addAll(allItems);
            } else {
                String q = query.toLowerCase();
                for (AppItem i : allItems) {
                    if (i.appName.toLowerCase().contains(q) || i.packageName.toLowerCase().contains(q)) {
                        filteredItems.add(i);
                    }
                }
            }
            notifyDataSetChanged();
        }

        void selectAll() {
            for (AppItem i : filteredItems) i.isSelected = true;
            notifyDataSetChanged();
            selectionCallback.run();
        }

        void clearAll() {
            for (AppItem i : filteredItems) i.isSelected = false;
            notifyDataSetChanged();
            selectionCallback.run();
        }

        int getSelectedCount() {
            int c = 0;
            for (AppItem i : allItems) {
                if (i.isSelected) c++;
            }
            return c;
        }

        List<AppItem> getSelectedItems() {
            List<AppItem> res = new ArrayList<>();
            for (AppItem i : allItems) {
                if (i.isSelected) res.add(i);
            }
            return res;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_freezer_app, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppItem item = filteredItems.get(position);
            holder.tvAppName.setText(item.appName);
            holder.cbAppSelect.setChecked(item.isSelected);
            if (item.icon != null) {
                holder.ivAppIcon.setImageDrawable(item.icon);
            } else {
                holder.ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon);
            }

            holder.itemView.setOnClickListener(v -> {
                item.isSelected = !item.isSelected;
                holder.cbAppSelect.setChecked(item.isSelected);
                selectionCallback.run();
            });
        }

        @Override
        public int getItemCount() {
            return filteredItems.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivAppIcon;
            TextView tvAppName;
            CheckBox cbAppSelect;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                ivAppIcon = itemView.findViewById(R.id.ivAppIcon);
                tvAppName = itemView.findViewById(R.id.tvAppName);
                cbAppSelect = itemView.findViewById(R.id.cbAppSelect);
            }
        }
    }
}
