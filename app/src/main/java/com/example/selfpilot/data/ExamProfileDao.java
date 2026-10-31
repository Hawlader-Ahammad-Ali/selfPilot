package com.example.selfpilot.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface ExamProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(ExamProfileEntity exam);

    @Update
    void update(ExamProfileEntity exam);

    @Query("SELECT * FROM exam_profiles ORDER BY startDate ASC")
    List<ExamProfileEntity> getAllExams();

    @Query("SELECT * FROM exam_profiles WHERE isActive = 1")
    List<ExamProfileEntity> getActiveExams();

    @Query("SELECT * FROM exam_profiles WHERE id = :id LIMIT 1")
    ExamProfileEntity getExamById(int id);
    
    @Query("SELECT * FROM exam_profiles WHERE status = 'PENDING'")
    List<ExamProfileEntity> getPendingExams();

    @Query("UPDATE exam_profiles SET isActive = 0, status = 'CANCELLED' WHERE status = 'PENDING' OR status = 'ACTIVE'")
    void deactivateAllExams();

    /**
     * XP earned by exams that ended within the given window. Used by the score calculator so
     * exam XP survives a recalculation instead of being overwritten.
     */
    @Query("SELECT COALESCE(SUM(xpEarned), 0) FROM exam_profiles WHERE endDate >= :startOfDay AND endDate < :endOfDay")
    int getExamXpBetween(long startOfDay, long endOfDay);

    /** Exam profiles that ran inside the window (cancelled ones never count). */
    @Query("SELECT COUNT(*) FROM exam_profiles WHERE endDate >= :startMillis AND startDate <= :endMillis AND status != 'CANCELLED'")
    int getExamCountBetween(long startMillis, long endMillis);
}
