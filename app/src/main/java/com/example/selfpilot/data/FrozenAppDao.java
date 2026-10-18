package com.example.selfpilot.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface FrozenAppDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(FrozenAppEntity app);

    @Update
    void update(FrozenAppEntity app);

    @Delete
    void delete(FrozenAppEntity app);

    @Query("SELECT * FROM frozen_apps WHERE isFrozen = 1 ORDER BY frozenAt DESC")
    List<FrozenAppEntity> getFrozenApps();

    @Query("SELECT * FROM frozen_apps WHERE packageName = :packageName LIMIT 1")
    FrozenAppEntity getFrozenApp(String packageName);

    @Query("SELECT COUNT(*) FROM frozen_apps WHERE isFrozen = 1")
    int getFrozenCount();

    @Query("UPDATE frozen_apps SET isFrozen = 0, unfrozenAt = :unfrozenAt WHERE packageName = :packageName")
    void unfreezeApp(String packageName, long unfrozenAt);
}
