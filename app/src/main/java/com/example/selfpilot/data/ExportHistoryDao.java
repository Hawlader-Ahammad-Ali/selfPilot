package com.example.selfpilot.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ExportHistoryDao {

    @Insert
    void insert(ExportHistoryEntity history);

    @Delete
    void delete(ExportHistoryEntity history);

    @Query("SELECT * FROM export_history ORDER BY exportDate DESC")
    List<ExportHistoryEntity> getAllHistory();
    
    @Query("DELETE FROM export_history")
    void clearHistory();
}
