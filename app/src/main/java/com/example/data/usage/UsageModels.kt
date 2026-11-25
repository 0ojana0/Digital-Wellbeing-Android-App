package com.example.data.usage

import android.graphics.drawable.Drawable

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

data class DailyAppUsage(
    val packageName: String,
    val appLabel: String,
    val icon: Drawable?,
    val usageMillis: Long,
    val launchCount: Int
) {
    val usageSeconds: Long get() = usageMillis / 1000L
    val usageMinutes: Int get() = (usageMillis / 60_000L).toInt()
}

data class DailyUsageSummary(
    val dayKey: String,
    val dayLabelShort: String,
    val dateDisplay: String,
    val isToday: Boolean,
    val totalUsageMillis: Long,
    val unlockCount: Int,
    val totalLaunches: Int,
    val apps: List<DailyAppUsage>
)

data class GuardPermissionsState(
    val usageAccessGranted: Boolean,
    val accessibilityEnabled: Boolean,
    val deviceAdminActive: Boolean,
    val notificationsGranted: Boolean,
    val ignoringBatteryOptimizations: Boolean
) {
    val coreProtectionReady: Boolean
        get() = usageAccessGranted && accessibilityEnabled

    val grantedCount: Int
        get() = listOf(
            usageAccessGranted,
            accessibilityEnabled,
            deviceAdminActive,
            notificationsGranted,
            ignoringBatteryOptimizations
        ).count { it }
}

enum class BlockReasonType {
    TIME_LIMIT,
    LAUNCH_LIMIT,
    FOCUS_MODE,
    ANTI_BYPASS_SHIELD
}

data class BlockDecision(
    val isBlocked: Boolean,
    val reasonType: BlockReasonType? = null,
    val targetPackage: String = "",
    val targetAppLabel: String = "",
    val headline: String = "",
    val detail: String = "",
    val unlockEpochMs: Long = 0L
)
