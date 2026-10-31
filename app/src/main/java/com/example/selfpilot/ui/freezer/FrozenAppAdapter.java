package com.example.selfpilot.ui.freezer;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.example.selfpilot.R;
import com.example.selfpilot.data.AppDatabase;
import com.example.selfpilot.data.AppInfoEntity;
import com.example.selfpilot.data.FrozenAppEntity;
import com.example.selfpilot.data.RestrictionRuleEntity;
import com.example.selfpilot.util.ShizukuUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FrozenAppAdapter extends RecyclerView.Adapter<FrozenAppAdapter.FrozenViewHolder> {

    private static final String TAG = "SELF_PILOT_FREEZER";

    private List<FrozenAppEntity> frozenList = new ArrayList<>();
    private final PackageManager packageManager;
    private final AppDatabase db;
    private final ExecutorService executorService;
    private final Runnable onListUpdatedCallback;

    public FrozenAppAdapter(PackageManager packageManager, AppDatabase db, Runnable onListUpdatedCallback) {
        this.packageManager = packageManager;
        this.db = db;
        this.onListUpdatedCallback = onListUpdatedCallback;
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public void setFrozenList(List<FrozenAppEntity> frozenList) {
        this.frozenList = frozenList;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public FrozenViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_frozen_app, parent, false);
        return new FrozenViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FrozenViewHolder holder, int position) {
        FrozenAppEntity item = frozenList.get(position);

        holder.tvAppName.setText(item.appName);

        CharSequence relativeTime = DateUtils.getRelativeTimeSpanString(
                item.frozenAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
        );
        holder.tvTime.setText(String.format("Frozen: %s", relativeTime));

        try {
            Drawable icon = packageManager.getApplicationIcon(item.packageName);
            holder.imgIcon.setImageDrawable(icon);
        } catch (PackageManager.NameNotFoundException e) {
            holder.imgIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        holder.btnUnfreeze.setOnClickListener(v -> confirmUnfreeze(v.getContext(), item));
        
        holder.btnSystemInfo.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + item.packageName));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            v.getContext().startActivity(intent);
        });
    }

    private void confirmUnfreeze(Context context, FrozenAppEntity item) {
        new AlertDialog.Builder(context)
                .setTitle("Unfreeze Application")
                .setMessage("Are you sure you want to unfreeze " + item.appName + "? This will restore access to launch the application.")
                .setPositiveButton("Unfreeze", (dialog, which) -> {
                    executorService.execute(() -> {
                        Log.i(TAG, "[UNFREEZE] Unfreezing pkg=" + item.packageName + " mode=" + item.restrictionMode);
                        if ("SHIZUKU".equals(item.restrictionMode)) {
                            ShizukuUtil.unfreezeApp(item.packageName);
                        }
                        com.example.selfpilot.util.FrozenAppsManager.unfreezeApp(context.getApplicationContext(), item.packageName);

                        if (onListUpdatedCallback != null) {
                            onListUpdatedCallback.run();
                        }
                    });
                    Toast.makeText(context, "Unfrozen " + item.appName, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public int getItemCount() {
        return frozenList.size();
    }

    static class FrozenViewHolder extends RecyclerView.ViewHolder {
        ImageView imgIcon;
        TextView tvAppName;
        TextView tvTime;
        Button btnUnfreeze;
        Button btnSystemInfo;

        public FrozenViewHolder(@NonNull View itemView) {
            super(itemView);
            imgIcon = itemView.findViewById(R.id.frozen_app_icon);
            tvAppName = itemView.findViewById(R.id.frozen_app_name);
            tvTime = itemView.findViewById(R.id.frozen_app_time);
            btnUnfreeze = itemView.findViewById(R.id.btn_unfreeze_item);
            btnSystemInfo = itemView.findViewById(R.id.btn_system_info_item);
        }
    }
}
