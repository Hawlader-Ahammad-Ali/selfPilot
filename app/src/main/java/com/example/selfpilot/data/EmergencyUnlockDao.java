package com.example.selfpilot.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

@Dao
public interface EmergencyUnlockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(EmergencyUnlockEntity entity);

    @Update
    void update(EmergencyUnlockEntity entity);

    @Query("SELECT * FROM emergency_unlocks WHERE date = :date LIMIT 1")
    EmergencyUnlockEntity getUnlockByDate(long date);
}
