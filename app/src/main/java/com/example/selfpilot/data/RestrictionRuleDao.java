package com.example.selfpilot.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface RestrictionRuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(RestrictionRuleEntity rule);

    @Update
    void update(RestrictionRuleEntity rule);

    @Query("SELECT * FROM restriction_rules")
    List<RestrictionRuleEntity> getAllRules();

    @Query("SELECT * FROM restriction_rules WHERE packageName = :packageName LIMIT 1")
    RestrictionRuleEntity getRuleByPackageName(String packageName);
}
