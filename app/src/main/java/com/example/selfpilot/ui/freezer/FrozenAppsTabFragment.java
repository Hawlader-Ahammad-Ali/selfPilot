package com.example.selfpilot.ui.freezer;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FrozenAppsTabFragment extends Fragment {

    private EditText etSearchFrozen;
    private Button btnSelectAllFrozen;
    private Button btnClearAllFrozen;
    private ProgressBar pbLoadingFrozen;
    private LinearLayout layoutEmptyFrozen;
    private RecyclerView rvFrozenAppsList;
    private ExtendedFloatingActionButton btnUnfreezeSelected;

    private FrozenAdapter adapter;
    private ExecutorService executorService;
    private AppDatabase db;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_freezer_frozen_apps, container, false);

        etSearchFrozen = view.findViewById(R.id.etSearchFrozen);
        btnSelectAllFrozen = view.findViewById(R.id.btnSelectAllFrozen);
        btnClearAllFrozen = view.findViewById(R.id.btnClearAllFrozen);
        pbLoadingFrozen = view.findViewById(R.id.pbLoadingFrozen);
        layoutEmptyFrozen = view.findViewById(R.id.layoutEmptyFrozen);
        rvFrozenAppsList = view.findViewById(R.id.rvFrozenAppsList);
        btnUnfreezeSelected = view.findViewById(R.id.btnUnfreezeSelected);

        db = AppDatabase.getDatabase(requireContext());
        executorService = Executors.newSingleThreadExecutor();

        rvFrozenAppsList.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new FrozenAdapter(() -> {
            int selectedCount = adapter.getSelectedCount();
            btnUnfreezeSelected.setText("Unfreeze " + selectedCount + " Apps");
            btnUnfreezeSelected.setEnabled(selectedCount > 0);
        });
        rvFrozenAppsList.setAdapter(adapter);

        setupListeners();

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadFrozenApps();
    }

    private void setupListeners() {
        etSearchFrozen.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnSelectAllFrozen.setOnClickListener(v -> adapter.selectAll());
        btnClearAllFrozen.setOnClickListener(v -> adapter.clearAll());

        btnUnfreezeSelected.setOnClickListener(v -> {
            List<FrozenAppEntity> selected = adapter.getSelectedItems();
            if (selected.isEmpty()) return;

            new AlertDialog.Builder(requireContext())
                    .setTitle("Confirm Unfreeze")
                    .setMessage("Unfreeze " + selected.size() + " selected application(s)?")
                    .setPositiveButton("Unfreeze Now", (dialog, which) -> unfreezeApps(selected))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private void loadFrozenApps() {
        pbLoadingFrozen.setVisibility(View.VISIBLE);
        rvFrozenAppsList.setVisibility(View.GONE);
        layoutEmptyFrozen.setVisibility(View.GONE);

        executorService.execute(() -> {
            List<FrozenAppEntity> frozenApps = db.frozenAppDao().getFrozenApps();
            PackageManager pm = requireContext().getPackageManager();

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    pbLoadingFrozen.setVisibility(View.GONE);
                    if (frozenApps == null || frozenApps.isEmpty()) {
                        layoutEmptyFrozen.setVisibility(View.VISIBLE);
                        adapter.setItems(new ArrayList<>(), pm);
                    } else {
                        rvFrozenAppsList.setVisibility(View.VISIBLE);
                        adapter.setItems(frozenApps, pm);
                    }
                });
            }
        });
    }

    private void unfreezeApps(List<FrozenAppEntity> appsToUnfreeze) {
        executorService.execute(() -> {
            for (FrozenAppEntity frozen : appsToUnfreeze) {
                String pkg = frozen.packageName;

                if ("SHIZUKU".equals(frozen.restrictionMode)) {
                    ShizukuUtil.unfreezeApp(pkg);
                }
                com.example.selfpilot.util.FrozenAppsManager.unfreezeApp(requireContext().getApplicationContext(), pkg);
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(requireContext(), appsToUnfreeze.size() + " app(s) unfrozen.", Toast.LENGTH_SHORT).show();
                    loadFrozenApps();
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

    class FrozenAdapter extends RecyclerView.Adapter<FrozenAdapter.ViewHolder> {
        private List<FrozenAppEntity> allItems = new ArrayList<>();
        private List<FrozenAppEntity> filteredItems = new ArrayList<>();
        private List<Boolean> selectionStates = new ArrayList<>();
        private List<Boolean> filteredSelectionStates = new ArrayList<>();
        private PackageManager pm;
        private Runnable selectionCallback;

        FrozenAdapter(Runnable selectionCallback) {
            this.selectionCallback = selectionCallback;
        }

        void setItems(List<FrozenAppEntity> items, PackageManager packageManager) {
            this.allItems = new ArrayList<>(items);
            this.filteredItems = new ArrayList<>(items);
            this.pm = packageManager;
            selectionStates.clear();
            filteredSelectionStates.clear();
            for (int i = 0; i < items.size(); i++) {
                selectionStates.add(false);
                filteredSelectionStates.add(false);
            }
            notifyDataSetChanged();
            selectionCallback.run();
        }

        void filter(String query) {
            filteredItems.clear();
            filteredSelectionStates.clear();
            if (query.isEmpty()) {
                filteredItems.addAll(allItems);
                filteredSelectionStates.addAll(selectionStates);
            } else {
                String q = query.toLowerCase();
                for (int i = 0; i < allItems.size(); i++) {
                    FrozenAppEntity item = allItems.get(i);
                    if (item.appName.toLowerCase().contains(q) || item.packageName.toLowerCase().contains(q)) {
                        filteredItems.add(item);
                        filteredSelectionStates.add(selectionStates.get(i));
                    }
                }
            }
            notifyDataSetChanged();
        }

        void selectAll() {
            for (int i = 0; i < filteredSelectionStates.size(); i++) {
                filteredSelectionStates.set(i, true);
                int globalIdx = allItems.indexOf(filteredItems.get(i));
                if (globalIdx != -1) {
                    selectionStates.set(globalIdx, true);
                }
            }
            notifyDataSetChanged();
            selectionCallback.run();
        }

        void clearAll() {
            for (int i = 0; i < filteredSelectionStates.size(); i++) {
                filteredSelectionStates.set(i, false);
                int globalIdx = allItems.indexOf(filteredItems.get(i));
                if (globalIdx != -1) {
                    selectionStates.set(globalIdx, false);
                }
            }
            notifyDataSetChanged();
            selectionCallback.run();
        }

        int getSelectedCount() {
            int c = 0;
            for (Boolean b : selectionStates) {
                if (b) c++;
            }
            return c;
        }

        List<FrozenAppEntity> getSelectedItems() {
            List<FrozenAppEntity> res = new ArrayList<>();
            for (int i = 0; i < allItems.size(); i++) {
                if (selectionStates.get(i)) res.add(allItems.get(i));
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
            FrozenAppEntity item = filteredItems.get(position);
            holder.tvAppName.setText(item.appName);

            CharSequence relativeTime = DateUtils.getRelativeTimeSpanString(
                    item.frozenAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            
            holder.tvAppStatus.setVisibility(View.VISIBLE);
            holder.tvAppStatus.setText("Frozen: " + relativeTime + " • " + item.restrictionMode);
            
            holder.cbAppSelect.setChecked(filteredSelectionStates.get(position));

            if (pm != null) {
                try {
                    Drawable icon = pm.getApplicationIcon(item.packageName);
                    holder.ivAppIcon.setImageDrawable(icon);
                } catch (Exception e) {
                    holder.ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon);
                }
            }

            holder.btnUnfreezeItem.setVisibility(View.VISIBLE);
            holder.btnUnfreezeItem.setOnClickListener(v -> {
                new AlertDialog.Builder(requireContext())
                        .setTitle("Unfreeze App?")
                        .setMessage("Do you want to unfreeze " + item.appName + "?")
                        .setPositiveButton("Unfreeze", (dialog, which) -> {
                            List<FrozenAppEntity> singleList = new ArrayList<>();
                            singleList.add(item);
                            unfreezeApps(singleList);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });

            holder.itemView.setOnClickListener(v -> {
                boolean isCurrentlyChecked = filteredSelectionStates.get(position);
                filteredSelectionStates.set(position, !isCurrentlyChecked);
                int globalIdx = allItems.indexOf(item);
                if (globalIdx != -1) {
                    selectionStates.set(globalIdx, !isCurrentlyChecked);
                }
                holder.cbAppSelect.setChecked(!isCurrentlyChecked);
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
            TextView tvAppStatus;
            CheckBox cbAppSelect;
            Button btnUnfreezeItem;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                ivAppIcon = itemView.findViewById(R.id.ivAppIcon);
                tvAppName = itemView.findViewById(R.id.tvAppName);
                tvAppStatus = itemView.findViewById(R.id.tvAppStatus);
                cbAppSelect = itemView.findViewById(R.id.cbAppSelect);
                btnUnfreezeItem = itemView.findViewById(R.id.btnUnfreezeItem);
            }
        }
    }
}
