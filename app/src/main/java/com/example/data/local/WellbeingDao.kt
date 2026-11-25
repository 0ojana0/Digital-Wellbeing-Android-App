package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppLimitDao {
    @Query("SELECT * FROM app_limits ORDER BY appLabel ASC")
    fun observeAllLimits(): Flow<List<AppLimitEntity>>

    @Query("SELECT * FROM app_limits")
    suspend fun getAllLimitsOnce(): List<AppLimitEntity>

    @Query("SELECT * FROM app_limits WHERE packageName = :packageName LIMIT 1")
    suspend fun getLimit(packageName: String): AppLimitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLimit(limit: AppLimitEntity)

    @Query("DELETE FROM app_limits WHERE packageName = :packageName")
    suspend fun deleteLimit(packageName: String)
}

@Dao
interface DailyLaunchStatDao {
    @Query("SELECT * FROM daily_launch_stats WHERE dayKey = :dayKey")
    fun observeStatsForDay(dayKey: String): Flow<List<DailyLaunchStatEntity>>

    @Query("SELECT * FROM daily_launch_stats WHERE dayKey = :dayKey")
    suspend fun getStatsForDayOnce(dayKey: String): List<DailyLaunchStatEntity>

    @Query("SELECT * FROM daily_launch_stats WHERE packageName = :packageName AND dayKey = :dayKey LIMIT 1")
    suspend fun getStat(packageName: String, dayKey: String): DailyLaunchStatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStat(stat: DailyLaunchStatEntity)

    @Query("DELETE FROM daily_launch_stats WHERE dayKey < :oldestDayKeyToKeep")
    suspend fun pruneOldStats(oldestDayKeyToKeep: String)
}

@Dao
interface FocusModeDao {
    @Query("SELECT * FROM focus_modes ORDER BY createdAtEpochMs DESC")
    fun observeAllFocusModes(): Flow<List<FocusModeEntity>>

    @Query("SELECT * FROM focus_modes")
    suspend fun getAllFocusModesOnce(): List<FocusModeEntity>

    @Query("SELECT * FROM focus_modes WHERE id = :id LIMIT 1")
    suspend fun getFocusModeById(id: Long): FocusModeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFocusMode(mode: FocusModeEntity): Long

    @Query("DELETE FROM focus_modes WHERE id = :id")
    suspend fun deleteFocusMode(id: Long)
}

@Dao
interface EnforcementStateDao {
    @Query("SELECT * FROM enforcement_state WHERE id = 1 LIMIT 1")
    fun observeState(): Flow<EnforcementStateEntity?>

    @Query("SELECT * FROM enforcement_state WHERE id = 1 LIMIT 1")
    suspend fun getStateOnce(): EnforcementStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: EnforcementStateEntity)
}
