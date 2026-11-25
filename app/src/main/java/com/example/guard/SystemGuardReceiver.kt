package com.example.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.WellbeingLockApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles boot recovery and system time/timezone/date change broadcasts so clock-tampering
 * attempts are immediately detected and strict enforcement resumes across reboots.
 */
class SystemGuardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pendingResult = goAsync()
        val app = WellbeingLockApplication.from(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_DATE_CHANGED -> {
                        app.container.enforcementEngine.onSystemClockChanged()
                    }

                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_LOCKED_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        val state = app.container.enforcementEngine.ensureStateAndCheckMidnight()
                        WellbeingLockApplication.scheduleNextMidnightAlarm(
                            context,
                            state.midnightDeadlineEpochMs
                        )
                        app.container.enforcementEngine.sweepAllLimitedAppsForBreaches()
                        StrictGuardForegroundService.startIfPermitted(context)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
