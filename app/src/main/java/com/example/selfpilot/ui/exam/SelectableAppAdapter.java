package com.example.selfpilot.ui.exam;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.selfpilot.R;

import java.util.ArrayList;
import java.util.List;

public class SelectableAppAdapter extends RecyclerView.Adapter<SelectableAppAdapter.ViewHolder> {

    public static class AppItem {
        public String packageName;
        public String appName;
        public Drawable icon;
        public boolean isSelected;

        public AppItem(String packageName, String appName, Drawable icon, boolean isSelected) {
            this.packageName = packageName;
            this.appName = appName;
            this.icon = icon;
            this.isSelected = isSelected;
        }
    }

    private List<AppItem> originalList = new ArrayList<>();
    private List<AppItem> filteredList = new ArrayList<>();

    public void setAppList(List<AppItem> appList) {
        this.originalList = appList;
        this.filteredList = new ArrayList<>(appList);
        notifyDataSetChanged();
    }

    public void filter(String query) {
        filteredList.clear();
        if (query.isEmpty()) {
            filteredList.addAll(originalList);
        } else {
            String lowerQuery = query.toLowerCase();
            for (AppItem item : originalList) {
                if (item.appName.toLowerCase().contains(lowerQuery) || item.packageName.toLowerCase().contains(lowerQuery)) {
                    filteredList.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    public void selectAll() {
        for (AppItem item : filteredList) {
            item.isSelected = true;
        }
        notifyDataSetChanged();
    }

    public void clearAll() {
        for (AppItem item : filteredList) {
            item.isSelected = false;
        }
        notifyDataSetChanged();
    }

    public List<String> getSelectedPackages() {
        List<String> selected = new ArrayList<>();
        for (AppItem item : originalList) {
            if (item.isSelected) {
                selected.add(item.packageName);
            }
        }
        return selected;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_selectable_app, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppItem item = filteredList.get(position);

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
        });
    }

    @Override
    public int getItemCount() {
        return filteredList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivAppIcon;
        TextView tvAppName;
        CheckBox cbAppSelect;

        ViewHolder(View itemView) {
            super(itemView);
            ivAppIcon = itemView.findViewById(R.id.ivAppIcon);
            tvAppName = itemView.findViewById(R.id.tvAppName);
            cbAppSelect = itemView.findViewById(R.id.cbAppSelect);
        }
    }
}
