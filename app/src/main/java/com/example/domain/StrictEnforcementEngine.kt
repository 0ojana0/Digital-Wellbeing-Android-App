package com.example.domain

import com.example.data.local.AppLimitDao
import com.example.data.local.AppLimitEntity
import com.example.data.local.DailyLaunchStatDao
import com.example.data.local.DailyLaunchStatEntity
import com.example.data.local.EnforcementStateDao
import com.example.data.local.EnforcementStateEntity
import com.example.data.local.FocusModeDao
import com.example.data.local.FocusModeEntity
import com.example.data.usage.BlockDecision
import com.example.data.usage.BlockReasonType
import com.example.data.usage.UsageStatsReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar

sealed class PolicyMutationResult {
    data class AppliedImmediately(val message: String, val triggeredLockout: Boolean = false) : PolicyMutationResult()
    data class ScheduledForTomorrow(val message: String) : PolicyMutationResult()
    data class Rejected(val reason: String) : PolicyMutationResult()
}

class StrictEnforcementEngine(
    private val appLimitDao: AppLimitDao,
    private val dailyLaunchStatDao: DailyLaunchStatDao,
    private val focusModeDao: FocusModeDao,
    private val enforcementStateDao: EnforcementStateDao,
    private val usageStatsRepository: UsageStatsReader,
    val clockTamperGuard: ClockTamperGuard = ClockTamperGuard()
) {
    private val mutex = Mutex()

    fun observeEnforcementState(): Flow<EnforcementStateEntity?> = enforcementStateDao.observeState()
    fun observeAllLimits(): Flow<List<AppLimitEntity>> = appLimitDao.observeAllLimits()
    fun observeAllFocusModes(): Flow<List<FocusModeEntity>> = focusModeDao.observeAllFocusModes()
    fun observeDailyStats(dayKey: String): Flow<List<DailyLaunchStatEntity>> =
        dailyLaunchStatDao.observeStatsForDay(dayKey)

    /**
     * Ensures EnforcementState exists, evaluates monotonic clock integrity, and applies midnight
     * rollover (resetting daily locks and promoting next-day pending limit increases/removals)
     * strictly when trusted monotonic midnight is reached.
     */
    suspend fun ensureStateAndCheckMidnight(): EnforcementStateEntity = mutex.withLock {
        val existing = enforcementStateDao.getStateOnce()
        if (existing == null) {
            val fresh = clockTamperGuard.createInitialState()
            enforcementStateDao.upsertState(fresh)
            return@withLock fresh
        }

        val eval = clockTamperGuard.evaluate(existing)
        if (eval.isMidnightReached) {
            val newDayKey = eval.currentWallDayKey
            val promotedState = clockTamperGuard.createInitialState(
                settingsShieldEnabled = existing.settingsShieldEnabled
            ).copy(
                currentLogicalDayKey = newDayKey,
                midnightDeadlineEpochMs = clockTamperGuard.computeNextMidnightMs(eval.trustedNowMs)
            )
            promotePendingLimitsForDay(newDayKey)
            enforcementStateDao.upsertState(promotedState)
            return@withLock promotedState
        }

        // Persist clock tamper detection or re-anchor after clean reboot
        val nowElapsed = clockTamperGuard.currentElapsedRealtime()
        val needsRebootReanchor = nowElapsed < existing.anchorElapsedRealtimeMs && !eval.tamperDetected
        if (eval.tamperDetected != existing.tamperDetected ||
            eval.tamperNote != existing.tamperNote ||
            needsRebootReanchor
        ) {
            val updated = existing.copy(
                tamperDetected = eval.tamperDetected,
                tamperNote = eval.tamperNote,
                anchorWallClockMs = if (needsRebootReanchor) eval.trustedNowMs else existing.anchorWallClockMs,
                anchorElapsedRealtimeMs = if (needsRebootReanchor) nowElapsed else existing.anchorElapsedRealtimeMs
            )
            enforcementStateDao.upsertState(updated)
            return@withLock updated
        }

        existing
    }

    private suspend fun promotePendingLimitsForDay(newDayKey: String) {
        val limits = appLimitDao.getAllLimitsOnce()
        for (limit in limits) {
            var nextTime = limit.dailyTimeLimitMinutes
            var nextLaunch = limit.dailyLaunchLimit
            var pendingTime = limit.pendingTimeLimitMinutes
            var pendingClearTime = limit.pendingClearTimeLimit
            var pendingLaunch = limit.pendingLaunchLimit
            var pendingClearLaunch = limit.pendingClearLaunchLimit
            var pendingDayKey = limit.pendingEffectiveDayKey

            if (limit.hasPendingChange && (pendingDayKey == null || pendingDayKey <= newDayKey)) {
                if (limit.pendingClearTimeLimit) {
                    nextTime = null
                } else if (limit.pendingTimeLimitMinutes != null) {
                    nextTime = limit.pendingTimeLimitMinutes
                }

                if (limit.pendingClearLaunchLimit) {
                    nextLaunch = null
                } else if (limit.pendingLaunchLimit != null) {
                    nextLaunch = limit.pendingLaunchLimit
                }

                pendingTime = null
                pendingClearTime = false
                pendingLaunch = null
                pendingClearLaunch = false
                pendingDayKey = null
            }

            val updated = limit.copy(
                dailyTimeLimitMinutes = nextTime,
                dailyLaunchLimit = nextLaunch,
                pendingTimeLimitMinutes = pendingTime,
                pendingClearTimeLimit = pendingClearTime,
                pendingLaunchLimit = pendingLaunch,
                pendingClearLaunchLimit = pendingClearLaunch,
                pendingEffectiveDayKey = pendingDayKey,
                lockedDayKey = null,
                lockedReason = null,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            if (!updated.hasActiveLimit && !updated.hasPendingChange) {
                appLimitDao.deleteLimit(updated.packageName)
            } else {
                appLimitDao.upsertLimit(updated)
            }
        }
    }

    /**
     * Explicit notification from BroadcastReceiver when system clock/timezone changes.
     */
    suspend fun onSystemClockChanged() {
        ensureStateAndCheckMidnight()
    }

    fun isSelfLocked(state: EnforcementStateEntity?): Boolean {
        if (state == null) return false
        return state.selfLockedDayKey != null && state.selfLockedDayKey == state.currentLogicalDayKey
    }

    /**
     * Enforces the user's asymmetric editing rule:
     * - While Wellbeing Lock is self-locked today -> ALL edits rejected.
     * - Decreasing time or launch limit -> applies IMMEDIATELY (and triggers dual lockout if already met).
     * - Increasing or removing time or launch limit -> queued for 00:00 TOMORROW only.
     */
    suspend fun updateAppLimit(
        packageName: String,
        appLabel: String,
        requestedTimeMinutes: Int?,
        requestedLaunchLimit: Int?
    ): PolicyMutationResult {
        val state = ensureStateAndCheckMidnight()
        if (isSelfLocked(state)) {
            return PolicyMutationResult.Rejected("Wellbeing Lock is locked until 00:00. Editing is prohibited.")
        }

        val eval = clockTamperGuard.evaluate(state)
        val todayKey = state.currentLogicalDayKey
        val tomorrowKey = clockTamperGuard.computeTomorrowDayKey(eval.trustedNowMs)
        val existing = appLimitDao.getLimit(packageName)

        if (existing?.lockedDayKey == todayKey) {
            return PolicyMutationResult.Rejected("$appLabel already hit its limit today and cannot be edited until 00:00.")
        }

        var nextActiveTime = existing?.dailyTimeLimitMinutes
        var nextPendingTime = existing?.pendingTimeLimitMinutes
        var nextPendingClearTime = existing?.pendingClearTimeLimit ?: false

        var nextActiveLaunch = existing?.dailyLaunchLimit
        var nextPendingLaunch = existing?.pendingLaunchLimit
        var nextPendingClearLaunch = existing?.pendingClearLaunchLimit ?: false

        var hadImmediateChange = false
        var hadTomorrowChange = false

        // Evaluate Time Limit change
        val currentTime = existing?.dailyTimeLimitMinutes
        if (requestedTimeMinutes != currentTime) {
            val isStricterTime = when {
                currentTime == null && requestedTimeMinutes != null -> true
                currentTime != null && requestedTimeMinutes != null && requestedTimeMinutes < currentTime -> true
                else -> false
            }
            if (isStricterTime) {
                nextActiveTime = requestedTimeMinutes
                nextPendingTime = null
                nextPendingClearTime = false
                hadImmediateChange = true
            } else {
                // Increasing or removing time limit -> delayed until 00:00 tomorrow
                if (requestedTimeMinutes == null) {
                    nextPendingTime = null
                    nextPendingClearTime = true
                } else {
                    nextPendingTime = requestedTimeMinutes
                    nextPendingClearTime = false
                }
                hadTomorrowChange = true
            }
        } else {
            // Matching active limit cancels any previously pending looser time limit
            if (nextPendingTime != null || nextPendingClearTime) {
                nextPendingTime = null
                nextPendingClearTime = false
                hadImmediateChange = true
            }
        }

        // Evaluate Launch Count Limit change
        val currentLaunch = existing?.dailyLaunchLimit
        if (requestedLaunchLimit != currentLaunch) {
            val isStricterLaunch = when {
                currentLaunch == null && requestedLaunchLimit != null -> true
                currentLaunch != null && requestedLaunchLimit != null && requestedLaunchLimit < currentLaunch -> true
                else -> false
            }
            if (isStricterLaunch) {
                nextActiveLaunch = requestedLaunchLimit
                nextPendingLaunch = null
                nextPendingClearLaunch = false
                hadImmediateChange = true
            } else {
                // Increasing or removing launch limit -> delayed until 00:00 tomorrow
                if (requestedLaunchLimit == null) {
                    nextPendingLaunch = null
                    nextPendingClearLaunch = true
                } else {
                    nextPendingLaunch = requestedLaunchLimit
                    nextPendingClearLaunch = false
                }
                hadTomorrowChange = true
            }
        } else {
            if (nextPendingLaunch != null || nextPendingClearLaunch) {
                nextPendingLaunch = null
                nextPendingClearLaunch = false
                hadImmediateChange = true
            }
        }

        val hasAnyPending = nextPendingTime != null ||
            nextPendingClearTime ||
            nextPendingLaunch != null ||
            nextPendingClearLaunch

        val updatedLimit = AppLimitEntity(
            packageName = packageName,
            appLabel = appLabel,
            dailyTimeLimitMinutes = nextActiveTime,
            dailyLaunchLimit = nextActiveLaunch,
            pendingTimeLimitMinutes = nextPendingTime,
            pendingClearTimeLimit = nextPendingClearTime,
            pendingLaunchLimit = nextPendingLaunch,
            pendingClearLaunchLimit = nextPendingClearLaunch,
            pendingEffectiveDayKey = if (hasAnyPending) tomorrowKey else null,
            lockedDayKey = existing?.lockedDayKey,
            lockedReason = existing?.lockedReason,
            updatedAtEpochMs = eval.trustedNowMs
        )

        if (!updatedLimit.hasActiveLimit && !updatedLimit.hasPendingChange) {
            appLimitDao.deleteLimit(packageName)
            return PolicyMutationResult.AppliedImmediately("No limits set for $appLabel.")
        }

        appLimitDao.upsertLimit(updatedLimit)

        // Immediately check if the stricter active limit is ALREADY breached by today's usage/launches
        val decision = evaluatePackageAccess(
            packageName = packageName,
            isNewLaunch = false,
            addActiveSeconds = 0L
        )
        if (decision.isBlocked && (decision.reasonType == BlockReasonType.TIME_LIMIT || decision.reasonType == BlockReasonType.LAUNCH_LIMIT)) {
            return PolicyMutationResult.AppliedImmediately(
                message = "$appLabel already reached the new limit. Both apps are now locked until 00:00.",
                triggeredLockout = true
            )
        }

        return when {
            hadImmediateChange && hadTomorrowChange -> PolicyMutationResult.ScheduledForTomorrow(
                "Stricter cap applied now. Looser change queued for 00:00 tomorrow."
            )
            hadTomorrowChange -> PolicyMutationResult.ScheduledForTomorrow(
                "Increase/removal scheduled for 00:00 tomorrow. Today's limit remains enforced."
            )
            else -> PolicyMutationResult.AppliedImmediately("Strict limit active immediately for $appLabel.")
        }
    }

    suspend fun cancelPendingChange(packageName: String): PolicyMutationResult {
        val state = ensureStateAndCheckMidnight()
        if (isSelfLocked(state)) {
            return PolicyMutationResult.Rejected("Wellbeing Lock is locked until 00:00.")
        }
        val existing = appLimitDao.getLimit(packageName)
            ?: return PolicyMutationResult.Rejected("No rule found.")
        val cleaned = existing.copy(
            pendingTimeLimitMinutes = null,
            pendingClearTimeLimit = false,
            pendingLaunchLimit = null,
            pendingClearLaunchLimit = false,
            pendingEffectiveDayKey = null
        )
        appLimitDao.upsertLimit(cleaned)
        return PolicyMutationResult.AppliedImmediately("Pending tomorrow change canceled. Strict limit kept.")
    }

    /**
     * Focus Mode management: strictly prohibits editing, disabling, or deleting any active Focus Mode.
     */
    suspend fun saveFocusMode(mode: FocusModeEntity): PolicyMutationResult {
        val state = ensureStateAndCheckMidnight()
        if (isSelfLocked(state)) {
            return PolicyMutationResult.Rejected("Wellbeing Lock is locked until 00:00.")
        }
        val trustedNow = clockTamperGuard.evaluate(state).trustedNowMs
        if (mode.id != 0L) {
            val existing = focusModeDao.getFocusModeById(mode.id)
            if (existing != null && isFocusModeActive(existing, trustedNow)) {
                return PolicyMutationResult.Rejected("Active Focus Mode cannot be edited until it ends.")
            }
        }
        if (mode.targetPackageSet().isEmpty()) {
            return PolicyMutationResult.Rejected("Select at least one app to block.")
        }
        focusModeDao.upsertFocusMode(mode)
        val activeNow = isFocusModeActive(mode, trustedNow)
        return PolicyMutationResult.AppliedImmediately(
            if (activeNow) "${mode.name} is now ACTIVE and strictly locked until it ends."
            else "${mode.name} saved."
        )
    }

    suspend fun toggleFocusMode(modeId: Long, enabled: Boolean): PolicyMutationResult {
        val state = ensureStateAndCheckMidnight()
        if (isSelfLocked(state)) {
            return PolicyMutationResult.Rejected("Wellbeing Lock is locked until 00:00.")
        }
        val existing = focusModeDao.getFocusModeById(modeId)
            ?: return PolicyMutationResult.Rejected("Focus Mode not found.")
        val trustedNow = clockTamperGuard.evaluate(state).trustedNowMs
        if (isFocusModeActive(existing, trustedNow)) {
            return PolicyMutationResult.Rejected("Cannot disable an active Focus Mode until its schedule ends.")
        }
        focusModeDao.upsertFocusMode(existing.copy(isEnabled = enabled))
        return PolicyMutationResult.AppliedImmediately(
            if (enabled) "${existing.name} enabled." else "${existing.name} disabled."
        )
    }

    suspend fun deleteFocusMode(modeId: Long): PolicyMutationResult {
        val state = ensureStateAndCheckMidnight()
        if (isSelfLocked(state)) {
            return PolicyMutationResult.Rejected("Wellbeing Lock is locked until 00:00.")
        }
        val existing = focusModeDao.getFocusModeById(modeId)
            ?: return PolicyMutationResult.Rejected("Focus Mode not found.")
        val trustedNow = clockTamperGuard.evaluate(state).trustedNowMs
        if (isFocusModeActive(existing, trustedNow)) {
            return PolicyMutationResult.Rejected("Active Focus Mode cannot be deleted until finished.")
        }
        focusModeDao.deleteFocusMode(modeId)
        return PolicyMutationResult.AppliedImmediately("${existing.name} deleted.")
    }

    suspend fun setSettingsShieldEnabled(enabled: Boolean): PolicyMutationResult {
        val state = ensureStateAndCheckMidnight()
        val trustedNow = clockTamperGuard.evaluate(state).trustedNowMs
        val activeFocus = focusModeDao.getAllFocusModesOnce().any { isFocusModeActive(it, trustedNow) }
        if (!enabled && (isSelfLocked(state) || activeFocus)) {
            return PolicyMutationResult.Rejected(
                "Anti-Bypass Settings Shield cannot be disabled while a lock or Focus Mode is active."
            )
        }
        enforcementStateDao.upsertState(state.copy(settingsShieldEnabled = enabled))
        return PolicyMutationResult.AppliedImmediately(
            if (enabled) "Settings & Uninstall Shield enabled." else "Settings Shield paused."
        )
    }

    /**
     * Evaluates whether [packageName] is currently blocked by:
     * 1) An active Focus Mode
     * 2) Already locked today
     * 3) Reaching its daily time limit
     * 4) Reaching its daily launch count limit
     *
     * Records debounced real-time launches and foreground seconds, and triggers DUAL LOCKOUT
     * (locking both the limited app AND Wellbeing Lock) the instant any daily limit is hit.
     */
    suspend fun evaluatePackageAccess(
        packageName: String,
        isNewLaunch: Boolean,
        addActiveSeconds: Long
    ): BlockDecision {
        val state = ensureStateAndCheckMidnight()
        val eval = clockTamperGuard.evaluate(state)
        val trustedNow = eval.trustedNowMs
        val todayKey = state.currentLogicalDayKey
        val label = usageStatsRepository.getAppLabel(packageName)

        // 1. Check active Focus Modes first (blocks app completely; does not need a timer)
        val focusModes = focusModeDao.getAllFocusModesOnce()
        val blockingFocus = focusModes.firstOrNull { mode ->
            isFocusModeActive(mode, trustedNow) && packageName in mode.targetPackageSet()
        }
        if (blockingFocus != null) {
            val unlockAt = getFocusModeEndEpochMs(blockingFocus, trustedNow)
            return BlockDecision(
                isBlocked = true,
                reasonType = BlockReasonType.FOCUS_MODE,
                targetPackage = packageName,
                targetAppLabel = label,
                headline = "Blocked by Focus Mode",
                detail = "\"${blockingFocus.name}\" strictly blocks $label until the session ends.",
                unlockEpochMs = unlockAt
            )
        }

        // 2. Check App Time & Launch Limits
        val limit = appLimitDao.getLimit(packageName) ?: return BlockDecision(isBlocked = false)
        if (!limit.hasActiveLimit && limit.lockedDayKey != todayKey) {
            return BlockDecision(isBlocked = false)
        }

        // Already locked today -> keep blocked until monotonic midnight
        if (limit.lockedDayKey == todayKey) {
            ensureDualSelfLock(state, label, limit.lockedReason ?: "Daily limit reached")
            return BlockDecision(
                isBlocked = true,
                reasonType = if (limit.lockedReason?.contains("launch", ignoreCase = true) == true) {
                    BlockReasonType.LAUNCH_LIMIT
                } else {
                    BlockReasonType.TIME_LIMIT
                },
                targetPackage = packageName,
                targetAppLabel = label,
                headline = "Daily Limit Reached",
                detail = limit.lockedReason ?: "$label reached its daily limit. Unlocks at 00:00.",
                unlockEpochMs = state.midnightDeadlineEpochMs
            )
        }

        // Reconcile real-time local counter with UsageStatsManager
        val existingStat = dailyLaunchStatDao.getStat(packageName, todayKey)
            ?: DailyLaunchStatEntity(packageName = packageName, dayKey = todayKey)

        val dayStartMs = clockTamperGuard.computeDayStartMs(trustedNow)
        val (usageStatsMs, usageStatsLaunches) = usageStatsRepository.getTodayPackageUsageSync(
            packageName = packageName,
            dayStartMs = dayStartMs,
            nowMs = trustedNow
        )

        val mergedLaunchesBase = maxOf(existingStat.launchCount, usageStatsLaunches)
        val newLaunchCount = if (isNewLaunch) mergedLaunchesBase + 1 else mergedLaunchesBase
        val mergedSecondsBase = maxOf(existingStat.trackedSeconds, usageStatsMs / 1000L)
        val newTrackedSeconds = mergedSecondsBase + addActiveSeconds.coerceAtLeast(0L)

        if (isNewLaunch || addActiveSeconds > 0L || newTrackedSeconds > existingStat.trackedSeconds || newLaunchCount > existingStat.launchCount) {
            dailyLaunchStatDao.upsertStat(
                existingStat.copy(
                    launchCount = newLaunchCount,
                    trackedSeconds = newTrackedSeconds,
                    lastLaunchEpochMs = if (isNewLaunch) trustedNow else existingStat.lastLaunchEpochMs
                )
            )
        }

        // Check Time Limit breach
        val timeLimitMinutes = limit.dailyTimeLimitMinutes
        if (timeLimitMinutes != null && newTrackedSeconds >= timeLimitMinutes * 60L) {
            val reason = "$label hit its ${formatMinutesShort(timeLimitMinutes)} daily time limit."
            lockAppAndSelf(state, limit, todayKey, reason)
            return BlockDecision(
                isBlocked = true,
                reasonType = BlockReasonType.TIME_LIMIT,
                targetPackage = packageName,
                targetAppLabel = label,
                headline = "Daily Time Limit Reached",
                detail = "$reason Both $label and Wellbeing Lock are strictly locked until 00:00.",
                unlockEpochMs = state.midnightDeadlineEpochMs
            )
        }

        // Check Launch Count Limit breach
        val launchLimit = limit.dailyLaunchLimit
        if (launchLimit != null && newLaunchCount > launchLimit) {
            val reason = "$label hit its $launchLimit-launch daily cap."
            lockAppAndSelf(state, limit, todayKey, reason)
            return BlockDecision(
                isBlocked = true,
                reasonType = BlockReasonType.LAUNCH_LIMIT,
                targetPackage = packageName,
                targetAppLabel = label,
                headline = "Daily Launch Limit Reached",
                detail = "$reason Both $label and Wellbeing Lock are strictly locked until 00:00.",
                unlockEpochMs = state.midnightDeadlineEpochMs
            )
        }

        return BlockDecision(isBlocked = false)
    }

    /**
     * Checks all limited apps against today's UsageStats so passive usage or previously accumulated
     * screen time triggers locks promptly even before the next app open.
     */
    suspend fun sweepAllLimitedAppsForBreaches() {
        val limits = appLimitDao.getAllLimitsOnce()
        for (limit in limits) {
            if (limit.hasActiveLimit) {
                evaluatePackageAccess(
                    packageName = limit.packageName,
                    isNewLaunch = false,
                    addActiveSeconds = 0L
                )
            }
        }
    }

    suspend fun shouldShieldSettingsNow(): Pair<Boolean, Long> {
        val state = ensureStateAndCheckMidnight()
        if (!state.settingsShieldEnabled) return false to 0L
        val trustedNow = clockTamperGuard.evaluate(state).trustedNowMs

        if (isSelfLocked(state)) {
            return true to state.midnightDeadlineEpochMs
        }

        val activeFocus = focusModeDao.getAllFocusModesOnce()
            .filter { isFocusModeActive(it, trustedNow) }
        if (activeFocus.isNotEmpty()) {
            val latestEnd = activeFocus.maxOf { getFocusModeEndEpochMs(it, trustedNow) }
            return true to latestEnd
        }

        // Also protect Settings when user has active limits configured so they can't disable
        // Accessibility or tamper with clock right before hitting a limit!
        val hasLimits = appLimitDao.getAllLimitsOnce().any { it.hasActiveLimit }
        if (hasLimits) {
            return true to state.midnightDeadlineEpochMs
        }

        return false to 0L
    }

    private suspend fun lockAppAndSelf(
        state: EnforcementStateEntity,
        limit: AppLimitEntity,
        todayKey: String,
        reason: String
    ) {
        appLimitDao.upsertLimit(
            limit.copy(
                lockedDayKey = todayKey,
                lockedReason = reason,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
        ensureDualSelfLock(state, limit.appLabel, reason)
    }

    private suspend fun ensureDualSelfLock(
        state: EnforcementStateEntity,
        appLabel: String,
        reason: String
    ) {
        val todayKey = state.currentLogicalDayKey
        val existingSummary = state.selfLockReasonSummary
        val newSummary = when {
            existingSummary.isNullOrBlank() -> reason
            existingSummary.contains(appLabel) -> existingSummary
            else -> "$existingSummary\n$reason"
        }
        if (state.selfLockedDayKey != todayKey || state.selfLockReasonSummary != newSummary) {
            enforcementStateDao.upsertState(
                state.copy(
                    selfLockedDayKey = todayKey,
                    selfLockReasonSummary = newSummary
                )
            )
        }
    }

    fun isFocusModeActive(mode: FocusModeEntity, nowMs: Long): Boolean {
        if (!mode.isEnabled) return false
        return when (mode.scheduleType) {
            FocusModeEntity.TYPE_ONE_TIME -> {
                nowMs >= mode.oneTimeStartEpochMs && nowMs < mode.oneTimeEndEpochMs
            }

            FocusModeEntity.TYPE_DAILY -> {
                val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
                val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                val todayBit = calendarDayToBit(cal.get(Calendar.DAY_OF_WEEK))
                if (mode.startMinuteOfDay < mode.endMinuteOfDay) {
                    (mode.daysOfWeekMask and todayBit) != 0 &&
                        minuteOfDay in mode.startMinuteOfDay until mode.endMinuteOfDay
                } else if (mode.startMinuteOfDay > mode.endMinuteOfDay) {
                    // Overnight window e.g. 22:00 -> 06:00
                    val yesterdayCal = Calendar.getInstance().apply {
                        timeInMillis = nowMs
                        add(Calendar.DAY_OF_YEAR, -1)
                    }
                    val yesterdayBit = calendarDayToBit(yesterdayCal.get(Calendar.DAY_OF_WEEK))
                    ((mode.daysOfWeekMask and todayBit) != 0 && minuteOfDay >= mode.startMinuteOfDay) ||
                        ((mode.daysOfWeekMask and yesterdayBit) != 0 && minuteOfDay < mode.endMinuteOfDay)
                } else {
                    false
                }
            }

            else -> false
        }
    }

    fun getFocusModeEndEpochMs(mode: FocusModeEntity, nowMs: Long): Long {
        return when (mode.scheduleType) {
            FocusModeEntity.TYPE_ONE_TIME -> mode.oneTimeEndEpochMs
            FocusModeEntity.TYPE_DAILY -> {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = nowMs
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val currentMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                cal.set(Calendar.HOUR_OF_DAY, mode.endMinuteOfDay / 60)
                cal.set(Calendar.MINUTE, mode.endMinuteOfDay % 60)
                if (mode.startMinuteOfDay > mode.endMinuteOfDay && currentMinute >= mode.startMinuteOfDay) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                cal.timeInMillis
            }

            else -> nowMs
        }
    }

    companion object {
        fun calendarDayToBit(calendarDayOfWeek: Int): Int = when (calendarDayOfWeek) {
            Calendar.MONDAY -> 1 shl 0
            Calendar.TUESDAY -> 1 shl 1
            Calendar.WEDNESDAY -> 1 shl 2
            Calendar.THURSDAY -> 1 shl 3
            Calendar.FRIDAY -> 1 shl 4
            Calendar.SATURDAY -> 1 shl 5
            Calendar.SUNDAY -> 1 shl 6
            else -> 0
        }

        fun formatMinutesShort(minutes: Int): String {
            val h = minutes / 60
            val m = minutes % 60
            return when {
                h > 0 && m > 0 -> "${h}h ${m}m"
                h > 0 -> "${h}h"
                else -> "${m}m"
            }
        }
    }
}
