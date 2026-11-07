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

    /** Used by the analytics screen for the "Honor Score (Avg)" card. */
    @Query("SELECT COALESCE(AVG(disciplineScore), 0) FROM honor_score WHERE date >= :startMillis")
    float getAverageDisciplineScoreSince(long startMillis);

    // --- Cloud backup / leaderboard -----------------------------------------------------

    /** Newest-first page used when exporting a backup. */
    @Query("SELECT * FROM honor_score ORDER BY date DESC LIMIT :limit")
    List<HonorScoreEntity> getScoresLimited(int limit);

    @Query("SELECT COALESCE(SUM(studyPoints), 0) FROM honor_score")
    int getTotalStudyPoints();

    @Query("SELECT * FROM honor_score ORDER BY date DESC LIMIT 1")
    HonorScoreEntity getLatestScore();
}
