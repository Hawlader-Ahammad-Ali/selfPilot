package com.example.selfpilot.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "goals")
public class GoalEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public long date;
    
    public int targetProductiveMins;
    public int actualProductiveMins;
    
    public int targetFocusSessions;
    public int actualFocusSessions;

    public GoalEntity(long date, int targetProductiveMins, int actualProductiveMins, int targetFocusSessions, int actualFocusSessions) {
        this.date = date;
        this.targetProductiveMins = targetProductiveMins;
        this.actualProductiveMins = actualProductiveMins;
        this.targetFocusSessions = targetFocusSessions;
        this.actualFocusSessions = actualFocusSessions;
    }
}
