package com.example.domain

import android.os.SystemClock
import com.example.data.local.EnforcementStateEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Protects daily midnight resets and active Focus Mode windows against system clock/timezone
 * manipulation by anchoring wall-clock deadlines to monotonic [SystemClock.elapsedRealtime].
 */
class ClockTamperGuard(
    private val wallClockProvider: () -> Long = { System.currentTimeMillis() },
    private val elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() }
) {
    data class ClockEvaluation(
        val trustedNowMs: Long,
        val currentWallDayKey: String,
        val isMidnightReached: Boolean,
        val remainingUntilMidnightMs: Long,
        val tamperDetected: Boolean,
        val tamperNote: String?
    )

    fun currentElapsedRealtime(): Long = elapsedRealtimeProvider()

    fun createInitialState(settingsShieldEnabled: Boolean = true): EnforcementStateEntity {
        val nowWall = wallClockProvider()
        val nowElapsed = elapsedRealtimeProvider()
        return EnforcementStateEntity(
            id = 1,
            currentLogicalDayKey = formatDayKey(nowWall),
            midnightDeadlineEpochMs = computeNextMidnightMs(nowWall),
            anchorWallClockMs = nowWall,
            anchorElapsedRealtimeMs = nowElapsed,
            selfLockedDayKey = null,
            selfLockReasonSummary = null,
            tamperDetected = false,
            tamperNote = null,
            settingsShieldEnabled = settingsShieldEnabled
        )
    }

    fun evaluate(state: EnforcementStateEntity): ClockEvaluation {
        val nowWall = wallClockProvider()
        val nowElapsed = elapsedRealtimeProvider()
        val elapsedDelta = nowElapsed - state.anchorElapsedRealtimeMs

        var tamperDetected = state.tamperDetected
        var tamperNote = state.tamperNote
        val trustedNowMs: Long

        if (elapsedDelta >= 0L) {
            // Same boot session: compare wall-clock progression with monotonic elapsedRealtime()
            val expectedWallNow = state.anchorWallClockMs + elapsedDelta
            val driftMs = abs(nowWall - expectedWallNow)
            if (driftMs > CLOCK_DRIFT_TOLERANCE_MS) {
                tamperDetected = true
                val direction = if (nowWall > expectedWallNow) "forward" else "backward"
                val minutesJump = (driftMs / 60_000L).coerceAtLeast(1L)
                tamperNote = "Clock moved $direction by ~${minutesJump}m. Lockout timer held on monotonic clock."
                trustedNowMs = expectedWallNow
            } else {
                trustedNowMs = nowWall
            }
        } else {
            // Device rebooted (elapsedRealtime reset to 0)
            if (nowWall < state.anchorWallClockMs - CLOCK_DRIFT_TOLERANCE_MS) {
                tamperDetected = true
                tamperNote = "System clock moved backward across reboot. Existing locks preserved."
                trustedNowMs = state.anchorWallClockMs
            } else {
                trustedNowMs = nowWall
            }
        }

        val remainingMs = (state.midnightDeadlineEpochMs - trustedNowMs).coerceAtLeast(0L)
        val isMidnightReached = trustedNowMs >= state.midnightDeadlineEpochMs
        val wallDayKey = formatDayKey(trustedNowMs)

        return ClockEvaluation(
            trustedNowMs = trustedNowMs,
            currentWallDayKey = wallDayKey,
            isMidnightReached = isMidnightReached,
            remainingUntilMidnightMs = remainingMs,
            tamperDetected = tamperDetected,
            tamperNote = tamperNote
        )
    }

    fun formatDayKey(epochMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMs))
    }

    fun computeTomorrowDayKey(baseEpochMs: Long): String {
        val cal = Calendar.getInstance().apply {
            timeInMillis = baseEpochMs
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return formatDayKey(cal.timeInMillis)
    }

    fun computeDayStartMs(epochMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = epochMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun computeNextMidnightMs(epochMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = epochMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    fun formatCountdown(remainingMs: Long): String {
        val totalSeconds = (remainingMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    companion object {
        // 2-minute tolerance for normal NTP / daylight-savings micro-adjustments
        private const val CLOCK_DRIFT_TOLERANCE_MS = 120_000L
    }
}
