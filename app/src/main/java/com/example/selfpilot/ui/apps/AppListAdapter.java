package com.example.selfpilot.ui.apps;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.AppInfoEntity;
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
            executorService.execute(() -> db.appInfoDao().update(appInfo));
        });
    }

    @Override
    public int getItemCount() {
        return appList.size();
    }

    static class AppViewHolder extends RecyclerView.ViewHolder {
        ImageView appIconImageView;
        TextView appNameTextView;
        MaterialSwitch studyAppSwitch;

        public AppViewHolder(@NonNull View itemView) {
            super(itemView);
            appIconImageView = itemView.findViewById(R.id.app_icon);
            appNameTextView = itemView.findViewById(R.id.app_name);
            studyAppSwitch = itemView.findViewById(R.id.study_app_switch);
        }
    }
}
