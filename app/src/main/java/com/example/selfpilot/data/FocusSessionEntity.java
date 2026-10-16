package com.example.selfpilot.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "focus_sessions")
public class FocusSessionEntity {

    @PrimaryKey(autoGenerate = true)
    public int id;

    public long date;
    public int durationMinutes;

    public FocusSessionEntity(long date, int durationMinutes) {
        this.date = date;
        this.durationMinutes = durationMinutes;
    }
}
