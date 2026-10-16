package com.example.selfpilot.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "app_info_table")
public class AppInfoEntity {
    @PrimaryKey
    @NonNull
    public String packageName;

    public String appName;

    public boolean isStudyApp;

    public String category; // "Study", "Productivity", "Communication", "Social Media", "Entertainment", "Games", "Other"

    public int dailyLimitMinutes; // 0 = unlimited

    public boolean isFrozen;

    public boolean isBlocked;

    public AppInfoEntity(@NonNull String packageName, String appName, boolean isStudyApp) {
        this.packageName = packageName;
        this.appName = appName;
        this.isStudyApp = isStudyApp;
        this.category = isStudyApp ? "Study" : "Other";
        this.dailyLimitMinutes = 0;
        this.isFrozen = false;
        this.isBlocked = false;
    }
}
