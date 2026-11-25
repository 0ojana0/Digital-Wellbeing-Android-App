package com.example.data.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.example.data.local.DailyLaunchStatEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

interface UsageStatsReader {
    fun getAppLabel(packageName: String): String
    fun getTodayPackageUsageSync(packageName: String, dayStartMs: Long, nowMs: Long): Pair<Long, Int>
}

class UsageStatsRepository(private val context: Context) : UsageStatsReader {

    private val usageStatsManager: UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val packageManager: PackageManager = context.packageManager

    private val iconCache = ConcurrentHashMap<String, Drawable?>()
    private val labelCache = ConcurrentHashMap<String, String>()

    private val ownPackageName: String = context.packageName

    suspend fun getInstalledLaunchableApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        val seen = HashSet<String>()
        resolveInfos.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == ownPackageName || !seen.add(pkg)) return@mapNotNull null
            val label = info.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
            val icon = runCatching { info.loadIcon(packageManager) }.getOrNull()
            labelCache[pkg] = label
            if (icon != null) iconCache[pkg] = icon
            InstalledAppInfo(
                packageName = pkg,
                label = label,
                icon = icon
            )
        }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    override fun getAppLabel(packageName: String): String {
        labelCache[packageName]?.let { return it }
        val resolved = runCatching {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        }.getOrElse { packageName }
        labelCache[packageName] = resolved
        return resolved
    }

    fun getAppIcon(packageName: String): Drawable? {
        iconCache[packageName]?.let { return it }
        val resolved = runCatching {
            packageManager.getApplicationIcon(packageName)
        }.getOrNull()
        if (resolved != null) iconCache[packageName] = resolved
        return resolved
    }

    /**
     * Queries the past 7 calendar days (index 0 = 6 days ago ... index 6 = today)
     * using real UsageStatsManager events and merges local tracked stats for today
     * so recorded usage and launches never drift downward.
     */
    suspend fun getPastSevenDaysUsage(
        todayTrackedStats: Map<String, DailyLaunchStatEntity>,
        referenceNowMs: Long = System.currentTimeMillis()
    ): List<DailyUsageSummary> = withContext(Dispatchers.IO) {
        val results = mutableListOf<DailyUsageSummary>()
        val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val shortDayFormat = SimpleDateFormat("EEE", Locale.getDefault())
        val dateDisplayFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

        for (daysAgo in 6 downTo 0) {
            val dayCal = Calendar.getInstance().apply {
                timeInMillis = referenceNowMs
                add(Calendar.DAY_OF_YEAR, -daysAgo)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startMs = dayCal.timeInMillis
            val endMs = if (daysAgo == 0) {
                referenceNowMs.coerceAtLeast(startMs + 1_000L)
            } else {
                startMs + 24L * 60L * 60L * 1000L - 1L
            }
            val dayKey = dayKeyFormat.format(dayCal.time)
            val isToday = daysAgo == 0
            val trackedForDay = if (isToday) todayTrackedStats else emptyMap()
            val summary = queryDaySummary(
                dayKey = dayKey,
                dayLabelShort = if (isToday) "Today" else shortDayFormat.format(dayCal.time),
                dateDisplay = dateDisplayFormat.format(dayCal.time),
                isToday = isToday,
                startMs = startMs,
                endMs = endMs,
                trackedStats = trackedForDay
            )
            results.add(summary)
        }
        results
    }

    /**
     * Fast single-package query for today's usage in milliseconds and launch count from UsageStatsManager.
     */
    override fun getTodayPackageUsageSync(
        packageName: String,
        dayStartMs: Long,
        nowMs: Long
    ): Pair<Long, Int> {
        val manager = usageStatsManager ?: return 0L to 0
        return try {
            val events = manager.queryEvents(dayStartMs, nowMs.coerceAtLeast(dayStartMs + 1_000L))
            val event = UsageEvents.Event()
            var activeStart: Long? = null
            var totalMs = 0L
            var launches = 0
            var lastForegroundPkg: String? = null

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED,
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        if (pkg == packageName) {
                            if (activeStart == null) {
                                activeStart = event.timeStamp
                            }
                            if (lastForegroundPkg != packageName) {
                                launches++
                            }
                        } else if (activeStart != null) {
                            totalMs += (event.timeStamp - activeStart).coerceAtLeast(0L)
                            activeStart = null
                        }
                        if (!isTransientSystemPackage(pkg)) {
                            lastForegroundPkg = pkg
                        }
                    }

                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.MOVE_TO_BACKGROUND,
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        if (pkg == packageName && activeStart != null) {
                            totalMs += (event.timeStamp - activeStart).coerceAtLeast(0L)
                            activeStart = null
                        }
                    }

                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        if (activeStart != null) {
                            totalMs += (event.timeStamp - activeStart).coerceAtLeast(0L)
                            activeStart = null
                        }
                        lastForegroundPkg = null
                    }
                }
            }
            if (activeStart != null) {
                totalMs += (nowMs - activeStart).coerceAtLeast(0L)
            }
            totalMs to launches
        } catch (_: SecurityException) {
            0L to 0
        }
    }

    private fun queryDaySummary(
        dayKey: String,
        dayLabelShort: String,
        dateDisplay: String,
        isToday: Boolean,
        startMs: Long,
        endMs: Long,
        trackedStats: Map<String, DailyLaunchStatEntity>
    ): DailyUsageSummary {
        val manager = usageStatsManager
        val usageMillisByPkg = HashMap<String, Long>()
        val launchesByPkg = HashMap<String, Int>()
        var unlockCount = 0

        if (manager != null) {
            try {
                val events = manager.queryEvents(startMs, endMs)
                val event = UsageEvents.Event()
                var currentForegroundPkg: String? = null
                var currentStartMs = 0L
                var lastNonTransientPkg: String? = null

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    val pkg = event.packageName
                    when (event.eventType) {
                        UsageEvents.Event.KEYGUARD_HIDDEN -> {
                            unlockCount++
                        }

                        UsageEvents.Event.ACTIVITY_RESUMED,
                        UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                            if (pkg != null) {
                                if (currentForegroundPkg != null && currentStartMs > 0L && currentForegroundPkg != pkg) {
                                    val elapsed = (event.timeStamp - currentStartMs).coerceIn(0L, 4L * 3600_000L)
                                    usageMillisByPkg[currentForegroundPkg] =
                                        (usageMillisByPkg[currentForegroundPkg] ?: 0L) + elapsed
                                }
                                currentForegroundPkg = pkg
                                currentStartMs = event.timeStamp

                                if (!isTransientSystemPackage(pkg) && pkg != ownPackageName) {
                                    if (lastNonTransientPkg != pkg) {
                                        launchesByPkg[pkg] = (launchesByPkg[pkg] ?: 0) + 1
                                    }
                                    lastNonTransientPkg = pkg
                                }
                            }
                        }

                        UsageEvents.Event.ACTIVITY_PAUSED,
                        UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                            if (pkg != null && pkg == currentForegroundPkg && currentStartMs > 0L) {
                                val elapsed = (event.timeStamp - currentStartMs).coerceIn(0L, 4L * 3600_000L)
                                usageMillisByPkg[pkg] = (usageMillisByPkg[pkg] ?: 0L) + elapsed
                                currentForegroundPkg = null
                                currentStartMs = 0L
                            }
                        }

                        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                            if (currentForegroundPkg != null && currentStartMs > 0L) {
                                val elapsed = (event.timeStamp - currentStartMs).coerceIn(0L, 4L * 3600_000L)
                                usageMillisByPkg[currentForegroundPkg] =
                                    (usageMillisByPkg[currentForegroundPkg] ?: 0L) + elapsed
                            }
                            currentForegroundPkg = null
                            currentStartMs = 0L
                            lastNonTransientPkg = null
                        }
                    }
                }

                if (currentForegroundPkg != null && currentStartMs > 0L && isToday) {
                    val elapsed = (endMs - currentStartMs).coerceIn(0L, 2L * 3600_000L)
                    usageMillisByPkg[currentForegroundPkg] =
                        (usageMillisByPkg[currentForegroundPkg] ?: 0L) + elapsed
                }

                // Fallback to aggregated stats if event log was truncated on older days
                if (usageMillisByPkg.isEmpty()) {
                    val aggregated = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startMs, endMs)
                    aggregated?.forEach { stat ->
                        if (stat.totalTimeInForeground > 0L && stat.lastTimeUsed >= startMs) {
                            usageMillisByPkg[stat.packageName] =
                                maxOf(usageMillisByPkg[stat.packageName] ?: 0L, stat.totalTimeInForeground)
                        }
                    }
                }
            } catch (_: SecurityException) {
                // Permission not granted yet
            }
        }

        // Merge local real-time tracked stats for today so counts are monotonic and never decrease
        trackedStats.forEach { (pkg, stat) ->
            val localMs = stat.trackedSeconds * 1000L
            usageMillisByPkg[pkg] = maxOf(usageMillisByPkg[pkg] ?: 0L, localMs)
            launchesByPkg[pkg] = maxOf(launchesByPkg[pkg] ?: 0, stat.launchCount)
        }

        val allPackages = (usageMillisByPkg.keys + launchesByPkg.keys)
            .filter { pkg ->
                pkg != ownPackageName &&
                    !isTransientSystemPackage(pkg) &&
                    ((usageMillisByPkg[pkg] ?: 0L) >= 1_000L || (launchesByPkg[pkg] ?: 0) > 0)
            }

        val appRows = allPackages.map { pkg ->
            DailyAppUsage(
                packageName = pkg,
                appLabel = getAppLabel(pkg),
                icon = getAppIcon(pkg),
                usageMillis = usageMillisByPkg[pkg] ?: 0L,
                launchCount = launchesByPkg[pkg] ?: 0
            )
        }.sortedByDescending { it.usageMillis }

        val totalUsage = appRows.sumOf { it.usageMillis }
        val totalLaunches = appRows.sumOf { it.launchCount }

        return DailyUsageSummary(
            dayKey = dayKey,
            dayLabelShort = dayLabelShort,
            dateDisplay = dateDisplay,
            isToday = isToday,
            totalUsageMillis = totalUsage,
            unlockCount = unlockCount,
            totalLaunches = totalLaunches,
            apps = appRows
        )
    }

    private fun isTransientSystemPackage(pkg: String): Boolean {
        return pkg == "com.android.systemui" ||
            pkg == "android" ||
            pkg.contains("inputmethod") ||
            pkg.contains("launcher") && pkg.startsWith("com.google.android.apps.nexuslauncher")
    }
}
