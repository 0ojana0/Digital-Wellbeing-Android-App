package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.WellbeingLockApplication
import com.example.data.local.AppLimitEntity
import com.example.data.local.DailyLaunchStatEntity
import com.example.data.local.EnforcementStateEntity
import com.example.data.local.FocusModeEntity
import com.example.data.usage.DailyUsageSummary
import com.example.data.usage.GuardPermissionsState
import com.example.data.usage.InstalledAppInfo
import com.example.domain.PolicyMutationResult
import com.example.guard.GuardPermissionsHelper
import com.example.guard.StrictGuardForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class WellbeingTab {
    DASHBOARD,
    LIMITS,
    FOCUS,
    GUARD
}

data class FocusModeUiItem(
    val entity: FocusModeEntity,
    val isActiveNow: Boolean,
    val remainingMillis: Long,
    val resolvedAppLabels: List<String>
)

data class WellbeingUiState(
    val isLoading: Boolean = true,
    val selectedTab: WellbeingTab = WellbeingTab.DASHBOARD,
    val selectedDayIndex: Int = 6, // 0..6 where 6 = Today
    val sevenDayUsage: List<DailyUsageSummary> = emptyList(),
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val limitsByPackage: Map<String, AppLimitEntity> = emptyMap(),
    val todayStatsByPackage: Map<String, DailyLaunchStatEntity> = emptyMap(),
    val focusModes: List<FocusModeUiItem> = emptyList(),
    val enforcementState: EnforcementStateEntity? = null,
    val permissionsState: GuardPermissionsState = GuardPermissionsState(
        usageAccessGranted = false,
        accessibilityEnabled = false,
        deviceAdminActive = false,
        notificationsGranted = false,
        ignoringBatteryOptimizations = false
    ),
    val isSelfLocked: Boolean = false,
    val remainingUntilMidnightMs: Long = 0L,
    val formattedMidnightCountdown: String = "00:00:00",
    val tamperDetected: Boolean = false,
    val tamperNote: String? = null,
    val hasAnyActiveFocusMode: Boolean = false,
    val searchQuery: String = "",
    val showOnlyLimitedApps: Boolean = false
) {
    val selectedDaySummary: DailyUsageSummary?
        get() = sevenDayUsage.getOrNull(selectedDayIndex) ?: sevenDayUsage.lastOrNull()

    val todaySummary: DailyUsageSummary?
        get() = sevenDayUsage.lastOrNull()

    val activeLimitsCount: Int
        get() = limitsByPackage.values.count { it.hasActiveLimit }
}

class WellbeingViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as WellbeingLockApplication
    private val engine = app.container.enforcementEngine
    private val usageRepo = app.container.usageStatsRepository
    private val clockGuard = app.container.clockTamperGuard

    private val _uiState = MutableStateFlow(WellbeingUiState())
    val uiState: StateFlow<WellbeingUiState> = _uiState.asStateFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    private val _tickTrigger = MutableStateFlow(0L)
    private var latestRawFocusModes: List<FocusModeEntity> = emptyList()
    private var latestTodayStats: Map<String, DailyLaunchStatEntity> = emptyMap()

    init {
        observeCoreFlows()
        startLiveCountdownTicker()
        refreshAllData()
    }

    private fun observeCoreFlows() {
        viewModelScope.launch {
            combine(
                engine.observeEnforcementState(),
                engine.observeAllLimits(),
                engine.observeAllFocusModes(),
                _tickTrigger
            ) { state, limits, focusModes, _ ->
                latestRawFocusModes = focusModes
                val limitsMap = limits.associateBy { it.packageName }
                val eval = state?.let { clockGuard.evaluate(it) }
                val trustedNow = eval?.trustedNowMs ?: System.currentTimeMillis()
                val selfLocked = engine.isSelfLocked(state)

                val focusItems = focusModes.map { mode ->
                    val active = engine.isFocusModeActive(mode, trustedNow)
                    val remaining = if (active) {
                        (engine.getFocusModeEndEpochMs(mode, trustedNow) - trustedNow).coerceAtLeast(0L)
                    } else {
                        0L
                    }
                    val labels = mode.targetPackageSet().map { usageRepo.getAppLabel(it) }.sorted()
                    FocusModeUiItem(
                        entity = mode,
                        isActiveNow = active,
                        remainingMillis = remaining,
                        resolvedAppLabels = labels
                    )
                }

                val remainingMidnight = eval?.remainingUntilMidnightMs ?: 0L
                Triple(
                    Quadruple(state, limitsMap, focusItems, selfLocked),
                    remainingMidnight,
                    eval
                )
            }.collect { (quad, remainingMidnight, eval) ->
                val (state, limitsMap, focusItems, selfLocked) = quad
                _uiState.update { current ->
                    current.copy(
                        enforcementState = state,
                        limitsByPackage = limitsMap,
                        focusModes = focusItems,
                        isSelfLocked = selfLocked,
                        remainingUntilMidnightMs = remainingMidnight,
                        formattedMidnightCountdown = clockGuard.formatCountdown(remainingMidnight),
                        tamperDetected = eval?.tamperDetected ?: false,
                        tamperNote = eval?.tamperNote,
                        hasAnyActiveFocusMode = focusItems.any { it.isActiveNow }
                    )
                }
            }
        }
    }

    private fun startLiveCountdownTicker() {
        viewModelScope.launch(Dispatchers.Default) {
            var secondsElapsed = 0
            while (isActive) {
                delay(1_000L)
                secondsElapsed++
                _tickTrigger.value = System.currentTimeMillis()
                // Every 10 seconds verify midnight rollover & sweep for newly breached timers
                if (secondsElapsed % 10 == 0) {
                    val state = engine.ensureStateAndCheckMidnight()
                    engine.sweepAllLimitedAppsForBreaches()
                    if (secondsElapsed % 30 == 0) {
                        reloadUsageDataInternal(state.currentLogicalDayKey)
                    }
                }
            }
        }
    }

    fun refreshAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            val permissions = GuardPermissionsHelper.inspectPermissions(app)
            val state = engine.ensureStateAndCheckMidnight()
            WellbeingLockApplication.scheduleNextMidnightAlarm(app, state.midnightDeadlineEpochMs)

            if (permissions.usageAccessGranted) {
                StrictGuardForegroundService.startIfPermitted(app)
            }

            engine.sweepAllLimitedAppsForBreaches()
            val installed = usageRepo.getInstalledLaunchableApps()

            _uiState.update {
                it.copy(
                    permissionsState = permissions,
                    installedApps = installed
                )
            }

            reloadUsageDataInternal(state.currentLogicalDayKey)
        }
    }

    private suspend fun reloadUsageDataInternal(dayKey: String) {
        val localStatsList = app.container.database.dailyLaunchStatDao().getStatsForDayOnce(dayKey)
        latestTodayStats = localStatsList.associateBy { it.packageName }
        val state = engine.ensureStateAndCheckMidnight()
        val trustedNow = clockGuard.evaluate(state).trustedNowMs
        val sevenDays = usageRepo.getPastSevenDaysUsage(
            todayTrackedStats = latestTodayStats,
            referenceNowMs = trustedNow
        )

        _uiState.update {
            it.copy(
                isLoading = false,
                sevenDayUsage = sevenDays,
                todayStatsByPackage = latestTodayStats
            )
        }
    }

    fun selectTab(tab: WellbeingTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun selectDayIndex(index: Int) {
        _uiState.update { it.copy(selectedDayIndex = index.coerceIn(0, 6)) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setShowOnlyLimited(onlyLimited: Boolean) {
        _uiState.update { it.copy(showOnlyLimitedApps = onlyLimited) }
    }

    fun saveAppLimit(
        packageName: String,
        appLabel: String,
        timeLimitMinutes: Int?,
        launchLimit: Int?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = engine.updateAppLimit(
                packageName = packageName,
                appLabel = appLabel,
                requestedTimeMinutes = timeLimitMinutes,
                requestedLaunchLimit = launchLimit
            )
            emitResultMessage(result)
            val state = engine.ensureStateAndCheckMidnight()
            reloadUsageDataInternal(state.currentLogicalDayKey)
        }
    }

    fun cancelPendingLimitChange(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = engine.cancelPendingChange(packageName)
            emitResultMessage(result)
        }
    }

    fun saveFocusMode(mode: FocusModeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = engine.saveFocusMode(mode)
            emitResultMessage(result)
        }
    }

    fun toggleFocusMode(modeId: Long, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = engine.toggleFocusMode(modeId, enabled)
            emitResultMessage(result)
        }
    }

    fun deleteFocusMode(modeId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = engine.deleteFocusMode(modeId)
            emitResultMessage(result)
        }
    }

    fun setSettingsShieldEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = engine.setSettingsShieldEnabled(enabled)
            emitResultMessage(result)
        }
    }

    private fun emitResultMessage(result: PolicyMutationResult) {
        val message = when (result) {
            is PolicyMutationResult.AppliedImmediately -> result.message
            is PolicyMutationResult.ScheduledForTomorrow -> result.message
            is PolicyMutationResult.Rejected -> result.reason
        }
        _snackbarMessages.tryEmit(message)
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
