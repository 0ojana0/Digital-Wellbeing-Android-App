package com.example.ui.limits

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.AppLimitEntity
import com.example.data.usage.InstalledAppInfo
import com.example.ui.WellbeingUiState
import com.example.ui.common.AppIconImage
import com.example.ui.common.StatusPill
import com.example.ui.common.formatDurationReadable
import com.example.ui.common.formatMinutesReadable
import com.example.ui.theme.LocalWellbeingColors
import com.example.ui.theme.MonoBadgeStyle

@Composable
fun LimitsScreen(
    uiState: WellbeingUiState,
    onSearchQueryChange: (String) -> Unit,
    onShowOnlyLimitedChange: (Boolean) -> Unit,
    onEditAppLimit: (packageName: String, appLabel: String, todayUsageMs: Long, todayLaunches: Int) -> Unit,
    onCancelPendingChange: (packageName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val todayKey = uiState.enforcementState?.currentLogicalDayKey
    val todayAppsByPkg = remember(uiState.todaySummary) {
        uiState.todaySummary?.apps?.associateBy { it.packageName }.orEmpty()
    }

    val filteredApps = remember(
        uiState.installedApps,
        uiState.limitsByPackage,
        uiState.searchQuery,
        uiState.showOnlyLimitedApps,
        todayAppsByPkg
    ) {
        val query = uiState.searchQuery.trim().lowercase()
        uiState.installedApps
            .filter { app ->
                val matchesQuery = query.isEmpty() ||
                    app.label.lowercase().contains(query) ||
                    app.packageName.lowercase().contains(query)
                val hasRule = uiState.limitsByPackage[app.packageName]?.let {
                    it.hasActiveLimit || it.hasPendingChange
                } == true
                matchesQuery && (!uiState.showOnlyLimitedApps || hasRule)
            }
            .sortedWith(
                compareByDescending<InstalledAppInfo> {
                    uiState.limitsByPackage[it.packageName]?.hasActiveLimit == true
                }.thenByDescending {
                    todayAppsByPkg[it.packageName]?.usageMillis ?: 0L
                }.thenBy { it.label.lowercase() }
            )
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "strict_rule_notice") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.limits_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item(key = "search_and_filter") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = { Text(stringResource(R.string.limits_search_hint)) },
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchQueryChange("") }) {
                                    Icon(imageVector = Icons.Filled.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("limits_search_field")
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !uiState.showOnlyLimitedApps,
                            onClick = { onShowOnlyLimitedChange(false) },
                            label = { Text("${stringResource(R.string.limits_filter_all)} (${uiState.installedApps.size})") },
                            modifier = Modifier.testTag("filter_all_apps_chip")
                        )
                        FilterChip(
                            selected = uiState.showOnlyLimitedApps,
                            onClick = { onShowOnlyLimitedChange(true) },
                            label = { Text("${stringResource(R.string.limits_filter_limited)} (${uiState.activeLimitsCount})") },
                            modifier = Modifier.testTag("filter_limited_apps_chip")
                        )
                    }
                }
            }

            if (filteredApps.isEmpty()) {
                item(key = "empty_limits_list") {
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
                            Text(
                                text = if (uiState.showOnlyLimitedApps) {
                                    "No apps have active limits yet."
                                } else {
                                    "No matching installed apps found."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(items = filteredApps, key = { it.packageName }) { appInfo ->
                    val limit = uiState.limitsByPackage[appInfo.packageName]
                    val todayUsage = todayAppsByPkg[appInfo.packageName]
                    val usageMs = todayUsage?.usageMillis ?: 0L
                    val launches = todayUsage?.launchCount ?: 0
                    val isLocked = limit?.lockedDayKey != null && limit.lockedDayKey == todayKey

                    LimitAppRow(
                        appInfo = appInfo,
                        todayUsageMs = usageMs,
                        todayLaunches = launches,
                        limit = limit,
                        isLockedToday = isLocked,
                        onClick = {
                            onEditAppLimit(appInfo.packageName, appInfo.label, usageMs, launches)
                        },
                        onCancelPending = { onCancelPendingChange(appInfo.packageName) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun LimitAppRow(
    appInfo: InstalledAppInfo,
    todayUsageMs: Long,
    todayLaunches: Int,
    limit: AppLimitEntity?,
    isLockedToday: Boolean,
    onClick: () -> Unit,
    onCancelPending: () -> Unit
) {
    val extendedColors = LocalWellbeingColors.current

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("limits_app_row_${appInfo.packageName}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIconImage(drawable = appInfo.icon, appLabel = appInfo.label, size = 40.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appInfo.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val usageLine = buildString {
                        append("Today: ")
                        append(formatDurationReadable(todayUsageMs))
                        limit?.dailyTimeLimitMinutes?.let { append(" / ${formatMinutesReadable(it)}") }
                        append("  •  ")
                        append("$todayLaunches")
                        limit?.dailyLaunchLimit?.let { append("/$it") }
                        append(if (todayLaunches == 1 && limit?.dailyLaunchLimit == null) " open" else " opens")
                    }
                    Text(
                        text = usageLine,
                        style = MonoBadgeStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = when {
                        isLockedToday -> Icons.Filled.Lock
                        limit?.hasActiveLimit == true -> Icons.Filled.HourglassTop
                        else -> Icons.Outlined.HourglassEmpty
                    },
                    contentDescription = "Edit limits for ${appInfo.label}",
                    tint = when {
                        isLockedToday -> extendedColors.locked
                        limit?.hasActiveLimit == true -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            if (isLockedToday) {
                Spacer(modifier = Modifier.height(8.dp))
                StatusPill(
                    text = "Locked until 00:00 — editing prohibited",
                    containerColor = extendedColors.lockedContainer,
                    contentColor = extendedColors.onLockedContainer,
                    icon = Icons.Filled.Lock
                )
            } else if (limit?.hasPendingChange == true) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val pendingDesc = buildList {
                        when {
                            limit.pendingClearTimeLimit -> add("Remove timer")
                            limit.pendingTimeLimitMinutes != null -> add("Timer ${formatMinutesReadable(limit.pendingTimeLimitMinutes)}")
                        }
                        when {
                            limit.pendingClearLaunchLimit -> add("Remove launch cap")
                            limit.pendingLaunchLimit != null -> add("${limit.pendingLaunchLimit} opens")
                        }
                    }.joinToString(", ")

                    StatusPill(
                        text = "00:00 tomorrow: $pendingDesc",
                        containerColor = extendedColors.warningContainer,
                        contentColor = extendedColors.onWarningContainer,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    TextButton(
                        onClick = onCancelPending,
                        modifier = Modifier.testTag("cancel_pending_${appInfo.packageName}")
                    ) {
                        Text("Keep strict")
                    }
                }
            }
        }
    }
}
