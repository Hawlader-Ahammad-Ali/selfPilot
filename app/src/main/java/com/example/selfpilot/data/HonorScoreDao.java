package com.example.selfpilot.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

@Dao
public interface HonorScoreDao {

    @Insert
    void insert(HonorScoreEntity score);

    @Update
    void update(HonorScoreEntity score);

    @Query("SELECT * FROM honor_score WHERE date = :date LIMIT 1")
    HonorScoreEntity getScoreByDate(long date);
}
