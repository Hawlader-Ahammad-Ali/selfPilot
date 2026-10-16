package com.example.selfpilot.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "restriction_rules")
public class RestrictionRuleEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;
    
    public String packageName;
    public int limitMinutes;
    public boolean isBlocked;
    public long unlockedUntil;
}
