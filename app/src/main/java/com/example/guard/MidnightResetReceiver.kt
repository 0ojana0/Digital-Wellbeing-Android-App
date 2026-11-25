package com.example.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.WellbeingLockApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Triggered at local 00:00 midnight to verify monotonic rollover, promote pending next-day limit
 * changes, unlock daily limits, and schedule the following midnight alarm.
 */
class MidnightResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val app = WellbeingLockApplication.from(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val state = app.container.enforcementEngine.ensureStateAndCheckMidnight()
                WellbeingLockApplication.scheduleNextMidnightAlarm(
                    context,
                    state.midnightDeadlineEpochMs
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
