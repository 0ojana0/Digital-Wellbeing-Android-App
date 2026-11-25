package com.example

import com.example.data.local.AppLimitDao
import com.example.data.local.AppLimitEntity
import com.example.data.local.DailyLaunchStatDao
import com.example.data.local.DailyLaunchStatEntity
import com.example.data.local.EnforcementStateDao
import com.example.data.local.EnforcementStateEntity
import com.example.data.local.FocusModeDao
import com.example.data.local.FocusModeEntity
import com.example.data.usage.BlockReasonType
import com.example.data.usage.UsageStatsReader
import com.example.domain.ClockTamperGuard
import com.example.domain.PolicyMutationResult
import com.example.domain.StrictEnforcementEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class StrictEnforcementEngineTest {

    private lateinit var limitDao: FakeAppLimitDao
    private lateinit var statDao: FakeDailyLaunchStatDao
    private lateinit var focusDao: FakeFocusModeDao
    private lateinit var stateDao: FakeEnforcementStateDao
    private var fakeWallClockMs: Long = 0L
    private var fakeElapsedMs: Long = 100_000L
    private lateinit var engine: StrictEnforcementEngine

    @Before
    fun setUp() {
        limitDao = FakeAppLimitDao()
        statDao = FakeDailyLaunchStatDao()
        focusDao = FakeFocusModeDao()
        stateDao = FakeEnforcementStateDao()

        fakeWallClockMs = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 24, 14, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        fakeElapsedMs = 100_000L

        val clockGuard = ClockTamperGuard(
            wallClockProvider = { fakeWallClockMs },
            elapsedRealtimeProvider = { fakeElapsedMs }
        )
        engine = StrictEnforcementEngine(
            appLimitDao = limitDao,
            dailyLaunchStatDao = statDao,
            focusModeDao = focusDao,
            enforcementStateDao = stateDao,
            usageStatsRepository = object : UsageStatsReader {
                override fun getAppLabel(packageName: String): String = packageName
                override fun getTodayPackageUsageSync(packageName: String, dayStartMs: Long, nowMs: Long): Pair<Long, Int> = 0L to 0
            },
            clockTamperGuard = clockGuard
        )
    }

    @Test
    fun `decreasing limit applies immediately while increasing queues for tomorrow`() = runTest {
        val setInitial = engine.updateAppLimit("com.video.app", "Video", 30, 5)
        assertTrue(setInitial is PolicyMutationResult.AppliedImmediately)

        val decrease = engine.updateAppLimit("com.video.app", "Video", 20, 3)
        assertTrue(decrease is PolicyMutationResult.AppliedImmediately)
        val afterDecrease = limitDao.getLimit("com.video.app")!!
        assertEquals(20, afterDecrease.dailyTimeLimitMinutes)
        assertEquals(3, afterDecrease.dailyLaunchLimit)

        val increase = engine.updateAppLimit("com.video.app", "Video", 60, null)
        assertTrue(increase is PolicyMutationResult.ScheduledForTomorrow)
        val afterIncrease = limitDao.getLimit("com.video.app")!!
        assertEquals(20, afterIncrease.dailyTimeLimitMinutes)
        assertEquals(3, afterIncrease.dailyLaunchLimit)
        assertEquals(60, afterIncrease.pendingTimeLimitMinutes)
        assertTrue(afterIncrease.pendingClearLaunchLimit)
    }

    @Test
    fun `hitting launch cap blocks target app and triggers dual self-lockout preventing edits`() = runTest {
        engine.updateAppLimit("com.social.app", "Social", null, 2)

        assertFalse(engine.evaluatePackageAccess("com.social.app", isNewLaunch = true, addActiveSeconds = 0L).isBlocked)
        assertFalse(engine.evaluatePackageAccess("com.social.app", isNewLaunch = true, addActiveSeconds = 0L).isBlocked)

        val breach = engine.evaluatePackageAccess("com.social.app", isNewLaunch = true, addActiveSeconds = 0L)
        assertTrue(breach.isBlocked)
        assertEquals(BlockReasonType.LAUNCH_LIMIT, breach.reasonType)

        val state = engine.ensureStateAndCheckMidnight()
        assertTrue(engine.isSelfLocked(state))

        val editAttempt = engine.updateAppLimit("com.other.app", "Other", 10, 5)
        assertTrue(editAttempt is PolicyMutationResult.Rejected)
    }

    @Test
    fun `clock tampering forward cannot bypass lockout while genuine monotonic midnight unlocks and promotes pending`() = runTest {
        engine.updateAppLimit("com.games.app", "Games", 10, null)
        engine.updateAppLimit("com.games.app", "Games", 45, null)

        val breach = engine.evaluatePackageAccess("com.games.app", isNewLaunch = false, addActiveSeconds = 600L)
        assertTrue(breach.isBlocked)
        assertTrue(engine.isSelfLocked(engine.ensureStateAndCheckMidnight()))

        // Tamper attempt: jump wall clock forward 12h while monotonic elapsedRealtime only moves 5s
        fakeWallClockMs += 12L * 3600_000L
        fakeElapsedMs += 5_000L
        val tamperedState = engine.ensureStateAndCheckMidnight()
        assertTrue(tamperedState.tamperDetected)
        assertTrue(engine.isSelfLocked(tamperedState))
        assertTrue(engine.evaluatePackageAccess("com.games.app", isNewLaunch = true, addActiveSeconds = 0L).isBlocked)

        // Restore real wall clock and genuinely advance past midnight (+11h on both wall & monotonic clock)
        fakeWallClockMs -= 12L * 3600_000L
        fakeWallClockMs += 11L * 3600_000L
        fakeElapsedMs += 11L * 3600_000L
        val afterMidnight = engine.ensureStateAndCheckMidnight()
        assertFalse(engine.isSelfLocked(afterMidnight))

        val promoted = limitDao.getLimit("com.games.app")!!
        assertEquals(45, promoted.dailyTimeLimitMinutes)
        assertNull(promoted.pendingTimeLimitMinutes)
        assertNull(promoted.lockedDayKey)
    }

    @Test
    fun `active Focus Mode blocks target app and prohibits editing disabling or deleting`() = runTest {
        val mode = FocusModeEntity(
            name = "Deep Work",
            targetPackagesCsv = "com.chat.app",
            scheduleType = FocusModeEntity.TYPE_ONE_TIME,
            oneTimeStartEpochMs = fakeWallClockMs - 60_000L,
            oneTimeEndEpochMs = fakeWallClockMs + 3600_000L,
            isEnabled = true
        )
        val saved = engine.saveFocusMode(mode)
        assertTrue(saved is PolicyMutationResult.AppliedImmediately)
        val storedId = focusDao.getAllFocusModesOnce().first().id

        val decision = engine.evaluatePackageAccess("com.chat.app", isNewLaunch = true, addActiveSeconds = 0L)
        assertTrue(decision.isBlocked)
        assertEquals(BlockReasonType.FOCUS_MODE, decision.reasonType)

        assertTrue(engine.toggleFocusMode(storedId, false) is PolicyMutationResult.Rejected)
        assertTrue(engine.deleteFocusMode(storedId) is PolicyMutationResult.Rejected)
        assertTrue(engine.saveFocusMode(mode.copy(id = storedId, name = "Changed")) is PolicyMutationResult.Rejected)

        // Advance past Focus end (+65m) -> now editable & deletable
        fakeWallClockMs += 65L * 60_000L
        fakeElapsedMs += 65L * 60_000L
        assertTrue(engine.deleteFocusMode(storedId) is PolicyMutationResult.AppliedImmediately)
    }
}

private class FakeAppLimitDao : AppLimitDao {
    private val map = MutableStateFlow<Map<String, AppLimitEntity>>(emptyMap())
    override fun observeAllLimits(): Flow<List<AppLimitEntity>> = map.map { it.values.toList() }
    override suspend fun getAllLimitsOnce(): List<AppLimitEntity> = map.value.values.toList()
    override suspend fun getLimit(packageName: String): AppLimitEntity? = map.value[packageName]
    override suspend fun upsertLimit(limit: AppLimitEntity) {
        map.value = map.value + (limit.packageName to limit)
    }
    override suspend fun deleteLimit(packageName: String) {
        map.value = map.value - packageName
    }
}

private class FakeDailyLaunchStatDao : DailyLaunchStatDao {
    private val map = MutableStateFlow<Map<Pair<String, String>, DailyLaunchStatEntity>>(emptyMap())
    override fun observeStatsForDay(dayKey: String): Flow<List<DailyLaunchStatEntity>> =
        map.map { it.values.filter { stat -> stat.dayKey == dayKey } }
    override suspend fun getStatsForDayOnce(dayKey: String): List<DailyLaunchStatEntity> =
        map.value.values.filter { it.dayKey == dayKey }
    override suspend fun getStat(packageName: String, dayKey: String): DailyLaunchStatEntity? =
        map.value[packageName to dayKey]
    override suspend fun upsertStat(stat: DailyLaunchStatEntity) {
        map.value = map.value + ((stat.packageName to stat.dayKey) to stat)
    }
    override suspend fun pruneOldStats(oldestDayKeyToKeep: String) {
        map.value = map.value.filterValues { it.dayKey >= oldestDayKeyToKeep }
    }
}

private class FakeFocusModeDao : FocusModeDao {
    private var nextId = 1L
    private val map = MutableStateFlow<Map<Long, FocusModeEntity>>(emptyMap())
    override fun observeAllFocusModes(): Flow<List<FocusModeEntity>> = map.map { it.values.toList() }
    override suspend fun getAllFocusModesOnce(): List<FocusModeEntity> = map.value.values.toList()
    override suspend fun getFocusModeById(id: Long): FocusModeEntity? = map.value[id]
    override suspend fun upsertFocusMode(mode: FocusModeEntity): Long {
        val id = if (mode.id == 0L) nextId++ else mode.id
        map.value = map.value + (id to mode.copy(id = id))
        return id
    }
    override suspend fun deleteFocusMode(id: Long) {
        map.value = map.value - id
    }
}

private class FakeEnforcementStateDao : EnforcementStateDao {
    private val state = MutableStateFlow<EnforcementStateEntity?>(null)
    override fun observeState(): Flow<EnforcementStateEntity?> = state
    override suspend fun getStateOnce(): EnforcementStateEntity? = state.value
    override suspend fun upsertState(state: EnforcementStateEntity) {
        this.state.value = state
    }
}
