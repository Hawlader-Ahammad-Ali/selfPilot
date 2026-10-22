package com.example.selfpilot.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface GoalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(GoalEntity goal);

    @Update
    void update(GoalEntity goal);

    @Query("SELECT * FROM goals WHERE date = :date LIMIT 1")
    GoalEntity getGoalByDate(long date);

    @Query("SELECT * FROM goals ORDER BY date DESC LIMIT 30")
    List<GoalEntity> getRecentGoals();
}
