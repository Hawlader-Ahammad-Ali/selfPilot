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
    public boolean isActive;

    public ExamProfileEntity(String examName, long startDate, long endDate, int dailyGoalMinutes, boolean isActive) {
        this.examName = examName;
        this.startDate = startDate;
        this.endDate = endDate;
        this.dailyGoalMinutes = dailyGoalMinutes;
        this.isActive = isActive;
    }
}
