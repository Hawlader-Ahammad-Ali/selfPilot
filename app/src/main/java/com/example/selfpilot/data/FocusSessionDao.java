package com.example.selfpilot.data;

import androidx.room.Dao;
import androidx.room.Insert;

@Dao
public interface FocusSessionDao {
    @Insert
    void insert(FocusSessionEntity focusSession);
}
