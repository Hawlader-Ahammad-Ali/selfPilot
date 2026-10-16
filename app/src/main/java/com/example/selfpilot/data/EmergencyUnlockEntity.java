package com.example.selfpilot.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "emergency_unlocks")
public class EmergencyUnlockEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;
    
    public long date;
    public int unlocksUsed;
}
