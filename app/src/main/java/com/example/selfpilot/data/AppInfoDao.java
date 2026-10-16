package com.example.selfpilot.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface AppInfoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(AppInfoEntity appInfo);

    @Update
    void update(AppInfoEntity appInfo);

    @Query("SELECT * FROM app_info_table ORDER BY appName ASC")
    List<AppInfoEntity> getAllApps();

    @Query("SELECT * FROM app_info_table WHERE packageName = :packageName LIMIT 1")
    AppInfoEntity getAppInfo(String packageName);
}
