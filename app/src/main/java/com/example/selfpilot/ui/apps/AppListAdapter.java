package com.example.selfpilot.ui.apps;

import android.app.usage.UsageStats;
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
import com.example.selfpilot.data.FrozenAppEntity;
import com.example.selfpilot.data.RestrictionRuleEntity;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.AppViewHolder> {

    private List<AppInfoEntity> appList = new ArrayList<>();
    private Map<String, UsageStats> usageStatsMap = new HashMap<>();
    private final PackageManager packageManager;
    private final AppDatabase db;
    private final ExecutorService executorService;

    public AppListAdapter(PackageManager packageManager, AppDatabase db) {
        this.packageManager = packageManager;
        this.db = db;
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public void setAppList(List<AppInfoEntity> appList, Map<String, UsageStats> stats) {
        this.appList = appList;
        this.usageStatsMap = stats != null ? stats : new HashMap<>();
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

        UsageStats stats = usageStatsMap.get(appInfo.packageName);
        long usedMins = stats != null ? (stats.getTotalTimeInForeground() / 60000) : 0;

        if (appInfo.dailyLimitMinutes > 0) {
            status.append(String.format(" • Used: %dm / Limit: %dm", usedMins, appInfo.dailyLimitMinutes));
        }

        if (appInfo.isFrozen) {
            status.append(" • FROZEN");
        } else if (appInfo.isBlocked || (appInfo.dailyLimitMinutes > 0 && usedMins >= appInfo.dailyLimitMinutes)) {
            status.append(" • RESTRICTED");
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

        holder.itemView.setOnClickListener(v -> {
            try {
                showAppDetailsDialog(v.getContext(), appInfo, position);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void showAppDetailsDialog(Context context, AppInfoEntity appInfo, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_app_config, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        ImageView ivDialogAppIcon = dialogView.findViewById(R.id.ivDialogAppIcon);
        TextView tvDialogAppName = dialogView.findViewById(R.id.tvDialogAppName);
        TextView tvDialogAppClassification = dialogView.findViewById(R.id.tvDialogAppClassification);
        View btnDialogClose = dialogView.findViewById(R.id.btnDialogClose);

        MaterialSwitch switchFreeze = dialogView.findViewById(R.id.switchFreeze);
        MaterialSwitch switchBlock = dialogView.findViewById(R.id.switchBlock);
        MaterialSwitch switchNotifications = dialogView.findViewById(R.id.switchNotifications);
        EditText etDailyLimit = dialogView.findViewById(R.id.etDailyLimit);

        View btnDialogCancel = dialogView.findViewById(R.id.btnDialogCancel);
        View btnDialogSave = dialogView.findViewById(R.id.btnDialogSave);

        try {
            Drawable icon = packageManager.getApplicationIcon(appInfo.packageName);
            ivDialogAppIcon.setImageDrawable(icon);
        } catch (PackageManager.NameNotFoundException ignored) {}

        tvDialogAppName.setText(appInfo.appName);
        tvDialogAppClassification.setText(appInfo.isStudyApp ? "Study App" : "Non-Study App");

        switchFreeze.setChecked(appInfo.isFrozen);
        switchBlock.setChecked(appInfo.isBlocked);
        switchNotifications.setChecked(appInfo.suppressNotifications);
        etDailyLimit.setText(String.valueOf(appInfo.dailyLimitMinutes));

        btnDialogClose.setOnClickListener(v -> dialog.dismiss());
        btnDialogCancel.setOnClickListener(v -> dialog.dismiss());

        btnDialogSave.setOnClickListener(v -> {
            boolean isFrozen = switchFreeze.isChecked();
            boolean isBlocked = switchBlock.isChecked();
            boolean suppressNotifs = switchNotifications.isChecked();
            
            int limit = 0;
            try {
                limit = Integer.parseInt(etDailyLimit.getText().toString().trim());
            } catch (Exception ignored) {
            }

            appInfo.isFrozen = isFrozen;
            appInfo.isBlocked = isBlocked || isFrozen;
            appInfo.suppressNotifications = suppressNotifs;
            appInfo.dailyLimitMinutes = limit;

            int finalLimit = limit;
            executorService.execute(() -> {
                db.appInfoDao().update(appInfo);

                if (isFrozen) {
                    com.example.selfpilot.util.FrozenAppsManager.freezeApp(context.getApplicationContext(), appInfo.packageName, appInfo.appName, "STANDARD");
                } else {
                    com.example.selfpilot.util.FrozenAppsManager.unfreezeApp(context.getApplicationContext(), appInfo.packageName);
                }

                RestrictionRuleEntity rule = db.restrictionRuleDao().getRuleByPackageName(appInfo.packageName);
                if (rule == null) {
                    rule = new RestrictionRuleEntity();
                    rule.packageName = appInfo.packageName;
                }
                rule.limitMinutes = finalLimit;
                rule.isBlocked = appInfo.isBlocked;
                rule.suppressNotifications = suppressNotifs;
                db.restrictionRuleDao().insert(rule);
            });

            notifyItemChanged(position);
            Toast.makeText(context, appInfo.appName + " settings updated.", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
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
