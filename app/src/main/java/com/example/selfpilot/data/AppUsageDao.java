package com.example.selfpilot.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface AppUsageDao {

    @Insert
    void insert(AppUsageEntity usage);

    @Query("SELECT * FROM app_usage WHERE date = :date")
    List<AppUsageEntity> getUsagesByDate(long date);

    @Query("SELECT * FROM app_usage WHERE packageName = :packageName AND date = :date LIMIT 1")
    AppUsageEntity getUsageByPackageAndDate(String packageName, long date);

    @Update
    void update(AppUsageEntity usage);
}
