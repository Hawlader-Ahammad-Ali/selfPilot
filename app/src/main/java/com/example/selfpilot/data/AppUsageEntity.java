package com.example.selfpilot.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "app_usage")
public class AppUsageEntity {

    @PrimaryKey(autoGenerate = true)
    private int id;

    private String packageName;
    private long date;
    private long totalTimeInForeground;
    private boolean isStudyApp;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public long getDate() {
        return date;
    }

    public void setDate(long date) {
        this.date = date;
    }

    public long getTotalTimeInForeground() {
        return totalTimeInForeground;
    }

    public void setTotalTimeInForeground(long totalTimeInForeground) {
        this.totalTimeInForeground = totalTimeInForeground;
    }

    public boolean isStudyApp() {
        return isStudyApp;
    }

    public void setStudyApp(boolean studyApp) {
        isStudyApp = studyApp;
    }
}
