package com.example.selfpilot.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {
        AppUsageEntity.class,
        HonorScoreEntity.class,
        AppInfoEntity.class,
        FocusSessionEntity.class,
        RestrictionRuleEntity.class,
        EmergencyUnlockEntity.class,
        ExamProfileEntity.class,
        AchievementEntity.class,
        FrozenAppEntity.class
}, version = 8, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    public abstract AppUsageDao appUsageDao();
    public abstract HonorScoreDao honorScoreDao();
    public abstract AppInfoDao appInfoDao();
    public abstract FocusSessionDao focusSessionDao();
    public abstract RestrictionRuleDao restrictionRuleDao();
    public abstract EmergencyUnlockDao emergencyUnlockDao();
    public abstract ExamProfileDao examProfileDao();
    public abstract AchievementDao achievementDao();
    public abstract FrozenAppDao frozenAppDao();

    private static volatile AppDatabase INSTANCE;

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            AppDatabase.class, "selfpilot_database")
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
