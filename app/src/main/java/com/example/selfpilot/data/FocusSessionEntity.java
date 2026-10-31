package com.example.selfpilot.data;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "focus_sessions")
public class FocusSessionEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public long startTime;
    public long endTime;
    public int durationMinutes;
    public int emergencyUnlocksUsed;
    public int xpEarned;
    public String status; // "ACTIVE", "COMPLETED", "FAILED"

    public FocusSessionEntity() {}

    @Ignore
    public FocusSessionEntity(long startTime, long endTime, int durationMinutes) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationMinutes = durationMinutes;
        this.emergencyUnlocksUsed = 0;
        this.xpEarned = 0;
        this.status = "ACTIVE";
    }
}
