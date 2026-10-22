package com.example.selfpilot.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface HonorScoreDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(HonorScoreEntity score);

    @Update
    void update(HonorScoreEntity score);

    @Query("SELECT * FROM honor_score WHERE date = :date LIMIT 1")
    HonorScoreEntity getScoreByDate(long date);

    @Query("SELECT * FROM honor_score ORDER BY date DESC LIMIT 30")
    List<HonorScoreEntity> getRecentScores();
}
