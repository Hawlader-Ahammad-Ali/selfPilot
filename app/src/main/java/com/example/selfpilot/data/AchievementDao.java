package com.example.selfpilot.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface AchievementDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(AchievementEntity achievement);

    @Update
    void update(AchievementEntity achievement);

    @Query("SELECT * FROM achievements ORDER BY id ASC")
    List<AchievementEntity> getAllAchievements();

    @Query("SELECT * FROM achievements WHERE achievementKey = :key LIMIT 1")
    AchievementEntity getByKey(String key);
}
