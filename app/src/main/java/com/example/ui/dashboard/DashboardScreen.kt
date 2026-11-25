package com.example.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.AppLimitEntity
import com.example.data.usage.DailyAppUsage
import com.example.data.usage.DailyUsageSummary
import com.example.ui.WellbeingUiState
import com.example.ui.common.AppIconImage
import com.example.ui.common.StatusPill
import com.example.ui.common.formatDurationReadable
import com.example.ui.common.formatMinutesReadable
import com.example.ui.theme.LocalWellbeingColors
import com.example.ui.theme.MonoBadgeStyle
import com.example.ui.theme.MonoMetricStyle
import kotlin.math.max

@Composable
fun DashboardScreen(
    uiState: WellbeingUiState,
    onSelectDay: (Int) -> Unit,
    onGrantUsageAccess: () -> Unit,
    onOpenGuardTab: () -> Unit,
    onOpenAppLimitEditor: (packageName: String, appLabel: String, todayUsageMs: Long, todayLaunches: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedDay = uiState.selectedDaySummary
    val todayKey = uiState.enforcementState?.currentLogicalDayKey
    val extendedColors = LocalWellbeingColors.current

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!uiState.permissionsState.usageAccessGranted) {
                item(key = "usage_permission_banner") {
                    UsagePermissionBanner(
                        onGrantUsageAccess = onGrantUsageAccess
                    )
                }
            } else if (!uiState.permissionsState.accessibilityEnabled) {
                item(key = "guard_setup_banner") {
                    GuardSetupBanner(onOpenGuardTab = onOpenGuardTab)
                }
            }

            // Digital Wellbeing Ring Card
            item(key = "usage_ring_card") {
                UsageRingCard(
                    summary = selectedDay,
                    activeLimitsCount = uiState.activeLimitsCount,
                    midnightCountdown = uiState.formattedMidnightCountdown
                )
            }

            // 7-Day Interactive Bar Chart
            if (uiState.sevenDayUsage.isNotEmpty()) {
                item(key = "seven_day_chart") {
                    SevenDayBarChartCard(
                        days = uiState.sevenDayUsage,
                        selectedIndex = uiState.selectedDayIndex,
                        onSelectDay = onSelectDay
                    )
                }
            }

            // App Activity Header
            item(key = "apps_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.dashboard_apps_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (selectedDay != null) {
                        Text(
                            text = selectedDay.dateDisplay,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            val apps = selectedDay?.apps.orEmpty()
            if (apps.isEmpty()) {
                item(key = "empty_apps") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Timer,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.dashboard_empty_day),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                val maxUsageMs = max(1L, apps.first().usageMillis)
                items(items = apps, key = { it.packageName }) { appUsage ->
                    val limit = uiState.limitsByPackage[appUsage.packageName]
                    val todayApp = uiState.todaySummary?.apps?.find { it.packageName == appUsage.packageName }
                    val todayUsageMs = todayApp?.usageMillis ?: if (selectedDay?.isToday == true) appUsage.usageMillis else 0L
                    val todayLaunches = todayApp?.launchCount ?: if (selectedDay?.isToday == true) appUsage.launchCount else 0

                    DashboardAppRow(
                        appUsage = appUsage,
                        maxUsageMs = maxUsageMs,
                        limit = limit,
                        isLockedToday = limit?.lockedDayKey != null && limit.lockedDayKey == todayKey,
                        onEditLimit = {
                            onOpenAppLimitEditor(
                                appUsage.packageName,
                                appUsage.appLabel,
                                todayUsageMs,
                                todayLaunches
                            )
                        }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun UsagePermissionBanner(onGrantUsageAccess: () -> Unit) {
    val extendedColors = LocalWellbeingColors.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = extendedColors.warningContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.dashboard_no_usage_permission_title),
                style = MaterialTheme.typography.titleSmall,
                color = extendedColors.onWarningContainer,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.dashboard_no_usage_permission_body),
                style = MaterialTheme.typography.bodySmall,
                color = extendedColors.onWarningContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onGrantUsageAccess,
                modifier = Modifier.testTag("grant_usage_access_button")
            ) {
                Text(text = stringResource(R.string.dashboard_grant_access))
            }
        }
    }
}

@Composable
private fun GuardSetupBanner(onOpenGuardTab: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Enable Accessibility Blocker",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "Required for strict real-time blocking and anti-bypass protection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            TextButton(
                onClick = onOpenGuardTab,
                modifier = Modifier.testTag("open_guard_setup_button")
            ) {
                Text("Setup")
            }
        }
    }
}

@Composable
private fun UsageRingCard(
    summary: DailyUsageSummary?,
    activeLimitsCount: Int,
    midnightCountdown: String
) {
    val extendedColors = LocalWellbeingColors.current
    val totalMs = summary?.totalUsageMillis ?: 0L
    val apps = summary?.apps.orEmpty()

    // Top 5 apps + "Other" slice, classic Android Digital Wellbeing style
    val topFive = apps.take(5)
    val otherMs = apps.drop(5).sumOf { it.usageMillis }
    val segments: List<Pair<String, Long>> = buildList {
        topFive.forEach { add(it.appLabel to it.usageMillis) }
        if (otherMs > 0L) add("Other" to otherMs)
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = summary?.dateDisplay ?: stringResource(R.string.dashboard_today),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusPill(
                    text = "Resets in $midnightCountdown",
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = Icons.Filled.Schedule,
                    useMonoFont = true
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Circular Digital Wellbeing Usage Ring
            Box(
                modifier = Modifier.size(204.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 18.dp.toPx()
                    val inset = strokeWidth / 2f
                    val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                    val topLeft = Offset(inset, inset)

                    drawArc(
                        color = extendedColors.ringTrack,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                    )

                    if (totalMs > 0L && segments.isNotEmpty()) {
                        var currentStart = -90f
                        val gapDegrees = if (segments.size > 1) 2.5f else 0f
                        segments.forEachIndexed { index, (_, sliceMs) ->
                            val fraction = sliceMs.toFloat() / totalMs.toFloat()
                            val rawSweep = fraction * 360f
                            val sweep = (rawSweep - gapDegrees).coerceAtLeast(1.5f)
                            val color = extendedColors.chartSegments[index % extendedColors.chartSegments.size]
                            drawArc(
                                color = color,
                                startAngle = currentStart,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                            )
                            currentStart += rawSweep
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (summary?.isToday == true) "Today" else (summary?.dayLabelShort ?: "Today"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatDurationReadable(totalMs),
                        style = MonoMetricStyle.copy(fontSize = 28.sp, lineHeight = 34.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("dashboard_total_screen_time")
                    )
                    Text(
                        text = stringResource(R.string.dashboard_screen_time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (segments.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        segments.chunked(2).forEachIndexed { rowIdx, pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                pair.forEachIndexed { colIdx, (name, ms) ->
                                    val colorIdx = rowIdx * 2 + colIdx
                                    val dotColor = extendedColors.chartSegments[colorIdx % extendedColors.chartSegments.size]
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(dotColor)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = name,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = formatDurationReadable(ms),
                                            style = MonoBadgeStyle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (pair.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(14.dp))

            // Unlocks / Launches / Active Limits strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricColumn(
                    icon = Icons.Outlined.LockOpen,
                    value = (summary?.unlockCount ?: 0).toString(),
                    label = stringResource(R.string.dashboard_unlocks)
                )
                MetricColumn(
                    icon = Icons.Outlined.Launch,
                    value = (summary?.totalLaunches ?: 0).toString(),
                    label = stringResource(R.string.dashboard_launches)
                )
                MetricColumn(
                    icon = Icons.Filled.HourglassTop,
                    value = activeLimitsCount.toString(),
                    label = stringResource(R.string.dashboard_active_rules)
                )
            }
        }
    }
}

@Composable
private fun MetricColumn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MonoMetricStyle,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SevenDayBarChartCard(
    days: List<DailyUsageSummary>,
    selectedIndex: Int,
    onSelectDay: (Int) -> Unit
) {
    val maxDayMs = max(1L, days.maxOfOrNull { it.totalUsageMillis } ?: 1L)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.dashboard_weekly_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                days.forEachIndexed { index, day ->
                    val isSelected = index == selectedIndex
                    val heightFraction = (day.totalUsageMillis.toFloat() / maxDayMs.toFloat())
                        .coerceIn(0.06f, 1f)
                    val barColor = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectDay(index) }
                            .padding(vertical = 4.dp)
                            .testTag("weekly_bar_$index")
                    ) {
                        Box(
                            modifier = Modifier
                                .height(72.dp)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(18.dp)
                                    .height((72f * heightFraction).dp)
                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                    .background(barColor)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (day.isToday) "Now" else day.dayLabelShort.take(3),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardAppRow(
    appUsage: DailyAppUsage,
    maxUsageMs: Long,
    limit: AppLimitEntity?,
    isLockedToday: Boolean,
    onEditLimit: () -> Unit
) {
    val extendedColors = LocalWellbeingColors.current
    val progress = if (limit?.dailyTimeLimitMinutes != null && limit.dailyTimeLimitMinutes > 0) {
        (appUsage.usageMillis.toFloat() / (limit.dailyTimeLimitMinutes * 60_000f)).coerceIn(0f, 1f)
    } else {
        (appUsage.usageMillis.toFloat() / maxUsageMs.toFloat()).coerceIn(0.02f, 1f)
    }

    val barColor = when {
        isLockedToday -> extendedColors.locked
        limit?.dailyTimeLimitMinutes != null && progress >= 0.8f -> extendedColors.warning
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEditLimit)
            .testTag("dashboard_app_row_${appUsage.packageName}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconImage(
                drawable = appUsage.icon,
                appLabel = appUsage.appLabel,
                size = 40.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = appUsage.appLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formatDurationReadable(appUsage.usageMillis),
                        style = MonoBadgeStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    color = barColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${appUsage.launchCount} ${if (appUsage.launchCount == 1) "open" else "opens"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isLockedToday) {
                        StatusPill(
                            text = "Locked until 00:00",
                            containerColor = extendedColors.lockedContainer,
                            contentColor = extendedColors.onLockedContainer,
                            icon = Icons.Filled.Lock
                        )
                    } else if (limit != null && limit.hasActiveLimit) {
                        val capSummary = buildList {
                            limit.dailyTimeLimitMinutes?.let { add(formatMinutesReadable(it)) }
                            limit.dailyLaunchLimit?.let { add("${it}x opens") }
                        }.joinToString(" • ")
                        StatusPill(
                            text = capSummary,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            useMonoFont = true
                        )
                    }
                    if (limit?.hasPendingChange == true) {
                        StatusPill(
                            text = "Change @ 00:00",
                            containerColor = extendedColors.warningContainer,
                            contentColor = extendedColors.onWarningContainer
                        )
                    }
                }
            }

            IconButton(
                onClick = onEditLimit,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("edit_limit_btn_${appUsage.packageName}")
            ) {
                Icon(
                    imageVector = when {
                        isLockedToday -> Icons.Filled.Lock
                        limit?.hasActiveLimit == true -> Icons.Filled.HourglassTop
                        else -> Icons.Outlined.HourglassEmpty
                    },
                    contentDescription = "Configure limits for ${appUsage.appLabel}",
                    tint = when {
                        isLockedToday -> extendedColors.locked
                        limit?.hasActiveLimit == true -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
