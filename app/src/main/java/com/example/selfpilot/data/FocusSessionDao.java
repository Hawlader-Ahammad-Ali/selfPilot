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
}
