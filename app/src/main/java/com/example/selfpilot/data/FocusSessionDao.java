package com.example.selfpilot.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface FocusSessionDao {
    @Insert
    long insert(FocusSessionEntity focusSession);

    @Update
    void update(FocusSessionEntity focusSession);

    @Query("SELECT * FROM focus_sessions WHERE status = 'ACTIVE' LIMIT 1")
    FocusSessionEntity getActiveSession();

    @Query("SELECT * FROM focus_sessions WHERE status = 'COMPLETED' AND startTime >= :startOfDay")
    List<FocusSessionEntity> getCompletedSessionsToday(long startOfDay);

    @Query("SELECT * FROM focus_sessions ORDER BY startTime DESC LIMIT 10")
    List<FocusSessionEntity> getRecentSessions();

    @Query("SELECT * FROM focus_sessions WHERE startTime >= :dateStart ORDER BY id DESC")
    List<FocusSessionEntity> getSessionsByDate(long dateStart);

    // --- Cloud backup / leaderboard -----------------------------------------------------

    /** Newest-first page used when exporting a backup. */
    @Query("SELECT * FROM focus_sessions ORDER BY startTime DESC LIMIT :limit")
    List<FocusSessionEntity> getSessionsLimited(int limit);

    /** Natural key used to avoid importing the same session twice. */
    @Query("SELECT * FROM focus_sessions WHERE startTime = :startTime LIMIT 1")
    FocusSessionEntity getSessionByStart(long startTime);

    @Query("SELECT COALESCE(SUM(xpEarned), 0) FROM focus_sessions WHERE status = 'COMPLETED'")
    int getTotalXp();

    @Query("SELECT COALESCE(SUM(durationMinutes), 0) FROM focus_sessions WHERE status = 'COMPLETED'")
    int getTotalFocusMinutes();

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE status = 'COMPLETED'")
    int getTotalSessions();
}
