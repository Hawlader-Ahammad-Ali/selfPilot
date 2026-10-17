package com.example.selfpilot.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "achievements")
public class AchievementEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String achievementKey;
    public String title;
    public String description;
    public boolean isUnlocked;
    public long unlockedAt;

    public AchievementEntity(String achievementKey, String title, String description, boolean isUnlocked, long unlockedAt) {
        this.achievementKey = achievementKey;
        this.title = title;
        this.description = description;
        this.isUnlocked = isUnlocked;
        this.unlockedAt = unlockedAt;
    }
}
