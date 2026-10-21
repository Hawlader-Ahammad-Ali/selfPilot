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
    public int startTimeMinutes = -1; // e.g. 21*60 = 1260 for 9 PM
    public int endTimeMinutes = -1;   // e.g. 8*60 = 480 for 8 AM
    public String daysOfWeek = "1,2,3,4,5,6,7"; // 1=Sun, 2=Mon...
    public boolean suppressNotifications = false;
}
