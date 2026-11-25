package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-app strict daily time limit and launch count limit.
 *
 * Asymmetric rule:
 * - Decreasing an active limit updates [dailyTimeLimitMinutes] / [dailyLaunchLimit] immediately.
 * - Increasing or removing a limit stores the change in pending* fields, promoted only at midnight
 *   when [pendingEffectiveDayKey] is reached.
 * - Once breached, [lockedDayKey] is set to today's logical day key (e.g. "2026-11-24") and blocks
 *   both the app and Wellbeing Lock until midnight.
 */
@Entity(tableName = "app_limits")
data class AppLimitEntity(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val dailyTimeLimitMinutes: Int? = null,
    val dailyLaunchLimit: Int? = null,
    val pendingTimeLimitMinutes: Int? = null,
    val pendingClearTimeLimit: Boolean = false,
    val pendingLaunchLimit: Int? = null,
    val pendingClearLaunchLimit: Boolean = false,
    val pendingEffectiveDayKey: String? = null,
    val lockedDayKey: String? = null,
    val lockedReason: String? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    val hasActiveLimit: Boolean
        get() = dailyTimeLimitMinutes != null || dailyLaunchLimit != null

    val hasPendingChange: Boolean
        get() = pendingTimeLimitMinutes != null ||
            pendingClearTimeLimit ||
            pendingLaunchLimit != null ||
            pendingClearLaunchLimit
}

/**
 * Real-time tracked launches and active foreground seconds observed by the guard service.
 * Combined with UsageStatsManager via max() so usage/launches can never be reduced or spoofed.
 */
@Entity(tableName = "daily_launch_stats", primaryKeys = ["packageName", "dayKey"])
data class DailyLaunchStatEntity(
    val packageName: String,
    val dayKey: String,
    val launchCount: Int = 0,
    val trackedSeconds: Long = 0L,
    val lastLaunchEpochMs: Long = 0L
)

/**
 * Focus Mode profile supporting either:
 * - DAILY_RECURRING: active on days matching [daysOfWeekMask] (bit 0 = Mon .. bit 6 = Sun)
 *   between [startMinuteOfDay] and [endMinuteOfDay] (supports overnight windows).
 * - ONE_TIME: active strictly between [oneTimeStartEpochMs] and [oneTimeEndEpochMs].
 *
 * While active, editing, disabling, or deleting this Focus Mode is strictly prohibited.
 */
@Entity(tableName = "focus_modes")
data class FocusModeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val targetPackagesCsv: String,
    val scheduleType: String, // "DAILY_RECURRING" or "ONE_TIME"
    val daysOfWeekMask: Int = 0b1111111, // Mon=1, Tue=2, Wed=4, Thu=8, Fri=16, Sat=32, Sun=64
    val startMinuteOfDay: Int = 9 * 60,
    val endMinuteOfDay: Int = 17 * 60,
    val oneTimeStartEpochMs: Long = 0L,
    val oneTimeEndEpochMs: Long = 0L,
    val isEnabled: Boolean = true,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    fun targetPackageSet(): Set<String> =
        targetPackagesCsv.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    companion object {
        const val TYPE_DAILY = "DAILY_RECURRING"
        const val TYPE_ONE_TIME = "ONE_TIME"
    }
}

/**
 * Singleton row (id = 1) storing logical day boundary, monotonic clock anchor for anti-tamper,
 * dual self-lockout state when any limit is hit, and anti-bypass Settings Shield preference.
 */
@Entity(tableName = "enforcement_state")
data class EnforcementStateEntity(
    @PrimaryKey val id: Int = 1,
    val currentLogicalDayKey: String,
    val midnightDeadlineEpochMs: Long,
    val anchorWallClockMs: Long,
    val anchorElapsedRealtimeMs: Long,
    val selfLockedDayKey: String? = null,
    val selfLockReasonSummary: String? = null,
    val tamperDetected: Boolean = false,
    val tamperNote: String? = null,
    val settingsShieldEnabled: Boolean = true
)
