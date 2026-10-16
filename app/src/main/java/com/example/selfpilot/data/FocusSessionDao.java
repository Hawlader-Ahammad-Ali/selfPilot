package com.example.selfpilot.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface FocusSessionDao {
    @Insert
    void insert(FocusSessionEntity focusSession);

    @Query("SELECT * FROM focus_sessions WHERE date >= :dateStart ORDER BY id DESC")
    List<FocusSessionEntity> getSessionsByDate(long dateStart);
}
