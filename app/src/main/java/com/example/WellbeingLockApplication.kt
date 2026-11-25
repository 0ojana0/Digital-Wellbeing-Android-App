package com.example

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.data.local.WellbeingDatabase
import com.example.data.usage.UsageStatsRepository
import com.example.domain.ClockTamperGuard
import com.example.domain.StrictEnforcementEngine
import com.example.guard.MidnightResetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val database: WellbeingDatabase = WellbeingDatabase.getInstance(context)
    val usageStatsRepository: UsageStatsRepository = UsageStatsRepository(context)
    val clockTamperGuard: ClockTamperGuard = ClockTamperGuard()
    val enforcementEngine: StrictEnforcementEngine = StrictEnforcementEngine(
        appLimitDao = database.appLimitDao(),
        dailyLaunchStatDao = database.dailyLaunchStatDao(),
        focusModeDao = database.focusModeDao(),
        enforcementStateDao = database.enforcementStateDao(),
        usageStatsRepository = usageStatsRepository,
        clockTamperGuard = clockTamperGuard
    )
}

class WellbeingLockApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            val state = container.enforcementEngine.ensureStateAndCheckMidnight()
            scheduleNextMidnightAlarm(this@WellbeingLockApplication, state.midnightDeadlineEpochMs)
        }
    }

    companion object {
        fun from(context: Context): WellbeingLockApplication =
            context.applicationContext as WellbeingLockApplication

        fun scheduleNextMidnightAlarm(context: Context, triggerAtEpochMs: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, MidnightResetReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_MIDNIGHT,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val safeTrigger = triggerAtEpochMs.coerceAtLeast(System.currentTimeMillis() + 5_000L)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTrigger, pendingIntent)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTrigger, pendingIntent)
                }
            } catch (_: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTrigger, pendingIntent)
            }
        }

        private const val REQUEST_CODE_MIDNIGHT = 9001
    }
}
