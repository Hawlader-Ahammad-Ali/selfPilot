package com.example.selfpilot.ui.apps;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.AppInfoEntity;
import com.example.selfpilot.data.RestrictionRuleEntity;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.AppViewHolder> {

    private List<AppInfoEntity> appList = new ArrayList<>();
    private final PackageManager packageManager;
    private final AppDatabase db;
    private final ExecutorService executorService;

    public AppListAdapter(PackageManager packageManager, AppDatabase db) {
        this.packageManager = packageManager;
        this.db = db;
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public void setAppList(List<AppInfoEntity> appList) {
        this.appList = appList;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppInfoEntity appInfo = appList.get(position);

        holder.appNameTextView.setText(appInfo.appName);

        StringBuilder status = new StringBuilder();
        if (appInfo.isStudyApp) {
            status.append("Study App");
        } else {
            status.append("Non-Study");
        }

        if (appInfo.isFrozen) {
            status.append(" • FROZEN");
        }
        if (appInfo.dailyLimitMinutes > 0) {
            status.append(" • Limit: ").append(appInfo.dailyLimitMinutes).append("m");
        }
        if (appInfo.isBlocked) {
            status.append(" • BLOCKED");
        }

        holder.appStatusTextView.setText(status.toString());

        // Remove listener to avoid triggering it while recycling
        holder.studyAppSwitch.setOnCheckedChangeListener(null);
        holder.studyAppSwitch.setChecked(appInfo.isStudyApp);

        try {
            Drawable icon = packageManager.getApplicationIcon(appInfo.packageName);
            holder.appIconImageView.setImageDrawable(icon);
        } catch (PackageManager.NameNotFoundException e) {
            holder.appIconImageView.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        holder.studyAppSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appInfo.isStudyApp = isChecked;
            if (isChecked) {
                appInfo.category = "Study";
            }
            executorService.execute(() -> db.appInfoDao().update(appInfo));
            notifyItemChanged(position);
        });

        holder.itemView.setOnClickListener(v -> showAppDetailsDialog(v.getContext(), appInfo, position));
    }

    private void showAppDetailsDialog(Context context, AppInfoEntity appInfo, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(appInfo.appName);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        TextView tvInfo = new TextView(context);
        tvInfo.setText("Configure restrictions, limits, and freeze status:");
        tvInfo.setPadding(0, 0, 0, 16);
        layout.addView(tvInfo);

        MaterialSwitch switchFreeze = new MaterialSwitch(context);
        switchFreeze.setText("App Freezer (Freeze App)");
        switchFreeze.setChecked(appInfo.isFrozen);
        layout.addView(switchFreeze);

        MaterialSwitch switchBlock = new MaterialSwitch(context);
        switchBlock.setText("App Lock (Block Launch)");
        switchBlock.setChecked(appInfo.isBlocked);
        layout.addView(switchBlock);

        TextView tvLimitLabel = new TextView(context);
        tvLimitLabel.setText("Daily Limit (Minutes, 0 = No Limit):");
        tvLimitLabel.setPadding(0, 16, 0, 4);
        layout.addView(tvLimitLabel);

        EditText etLimit = new EditText(context);
        etLimit.setInputType(InputType.TYPE_CLASS_NUMBER);
        etLimit.setText(String.valueOf(appInfo.dailyLimitMinutes));
        layout.addView(etLimit);

        builder.setView(layout);

        builder.setPositiveButton("Save", (dialog, which) -> {
            boolean isFrozen = switchFreeze.isChecked();
            boolean isBlocked = switchBlock.isChecked();
            int limit = 0;
            try {
                limit = Integer.parseInt(etLimit.getText().toString().trim());
            } catch (Exception ignored) {
            }

            appInfo.isFrozen = isFrozen;
            appInfo.isBlocked = isBlocked || isFrozen;
            appInfo.dailyLimitMinutes = limit;

            int finalLimit = limit;
            executorService.execute(() -> {
                db.appInfoDao().update(appInfo);

                RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(appInfo.packageName);
                if (rule == null) {
                    rule = new RestrictionRuleEntity();
                    rule.packageName = appInfo.packageName;
                }
                rule.limitMinutes = finalLimit;
                rule.isBlocked = appInfo.isBlocked;
                db.restrictionRuleDao().insert(rule);
            });

            notifyItemChanged(position);
            Toast.makeText(context, appInfo.appName + " settings updated.", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    @Override
    public int getItemCount() {
        return appList.size();
    }

    static class AppViewHolder extends RecyclerView.ViewHolder {
        ImageView appIconImageView;
        TextView appNameTextView;
        TextView appStatusTextView;
        MaterialSwitch studyAppSwitch;

        public AppViewHolder(@NonNull View itemView) {
            super(itemView);
            appIconImageView = itemView.findViewById(R.id.app_icon);
            appNameTextView = itemView.findViewById(R.id.app_name);
            appStatusTextView = itemView.findViewById(R.id.app_status);
            studyAppSwitch = itemView.findViewById(R.id.study_app_switch);
        }
    }
}
