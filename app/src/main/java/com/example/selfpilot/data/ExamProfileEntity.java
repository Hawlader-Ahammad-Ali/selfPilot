package com.example.selfpilot.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "exam_profiles")
public class ExamProfileEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String examName;
    public long startDate;
    public long endDate;
    public int dailyGoalMinutes;
    
    public String restrictedAppsCsv; // comma separated package names
    public String allowedAppsCsv;    // comma separated package names
    
    public String status; // "PENDING", "ACTIVE", "COMPLETED", "CANCELLED"
    public boolean isActive;

    public int emergencyUnlocksUsed;
    public int xpEarned;

    public ExamProfileEntity() {}

    public ExamProfileEntity(String examName, long startDate, long endDate, int dailyGoalMinutes, String restrictedAppsCsv, String allowedAppsCsv) {
        this.examName = examName;
        this.startDate = startDate;
        this.endDate = endDate;
        this.dailyGoalMinutes = dailyGoalMinutes;
        this.restrictedAppsCsv = restrictedAppsCsv;
        this.allowedAppsCsv = allowedAppsCsv;
        this.status = "PENDING";
        this.isActive = false;
        this.emergencyUnlocksUsed = 0;
        this.xpEarned = 0;
    }
}
