package com.example.ui.limits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.AppLimitEntity
import com.example.ui.common.formatDurationReadable
import com.example.ui.common.formatMinutesReadable
import com.example.ui.theme.LocalWellbeingColors
import com.example.ui.theme.MonoMetricStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppLimitEditorSheet(
    packageName: String,
    appLabel: String,
    todayUsageMs: Long,
    todayLaunches: Int,
    existingLimit: AppLimitEntity?,
    onDismiss: () -> Unit,
    onSave: (timeLimitMinutes: Int?, launchLimit: Int?) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val extendedColors = LocalWellbeingColors.current

    var timeLimitEnabled by remember(existingLimit) {
        mutableStateOf(existingLimit?.dailyTimeLimitMinutes != null)
    }
    var timeMinutes by remember(existingLimit) {
        mutableIntStateOf(existingLimit?.dailyTimeLimitMinutes ?: 30)
    }

    var launchLimitEnabled by remember(existingLimit) {
        mutableStateOf(existingLimit?.dailyLaunchLimit != null)
    }
    var launchLimit by remember(existingLimit) {
        mutableIntStateOf(existingLimit?.dailyLaunchLimit ?: 5)
    }

    val draftTime = if (timeLimitEnabled) timeMinutes else null
    val draftLaunch = if (launchLimitEnabled) launchLimit else null

    val currentTime = existingLimit?.dailyTimeLimitMinutes
    val currentLaunch = existingLimit?.dailyLaunchLimit

    val timeIsLooser = (currentTime != null && (draftTime == null || draftTime > currentTime))
    val launchIsLooser = (currentLaunch != null && (draftLaunch == null || draftLaunch > currentLaunch))
    val hasLooserChange = timeIsLooser || launchIsLooser

    val timeIsStricter = (draftTime != null && (currentTime == null || draftTime < currentTime))
    val launchIsStricter = (draftLaunch != null && (currentLaunch == null || draftLaunch < currentLaunch))
    val hasStricterChange = timeIsStricter || launchIsStricter

    val willTriggerLockoutNow = (draftTime != null && timeIsStricter && todayUsageMs >= draftTime * 60_000L) ||
        (draftLaunch != null && launchIsStricter && todayLaunches > draftLaunch)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Text(
                    text = appLabel,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Used today: ${formatDurationReadable(todayUsageMs)} • $todayLaunches ${if (todayLaunches == 1) "open" else "opens"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Daily Time Limit Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.limits_time_limit),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = currentTime?.let { "Active today: ${formatMinutesReadable(it)}" }
                            ?: "No daily timer set",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = timeLimitEnabled,
                    onCheckedChange = { timeLimitEnabled = it },
                    modifier = Modifier.testTag("toggle_time_limit_switch")
                )
            }

            if (timeLimitEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = { timeMinutes = (timeMinutes - 5).coerceAtLeast(1) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("decrease_time_limit_btn")
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = "Decrease time limit by 5 minutes")
                    }
                    Text(
                        text = formatMinutesReadable(timeMinutes),
                        style = MonoMetricStyle.copy(fontSize = 24.sp),
                        modifier = Modifier.testTag("draft_time_limit_text")
                    )
                    FilledTonalIconButton(
                        onClick = { timeMinutes = (timeMinutes + 5).coerceAtMost(720) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("increase_time_limit_btn")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Increase time limit by 5 minutes")
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(5, 15, 30, 45, 60, 120).forEach { preset ->
                        FilterChip(
                            selected = timeMinutes == preset,
                            onClick = { timeMinutes = preset },
                            label = { Text(formatMinutesReadable(preset)) }
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Daily Launch Count Limit Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.limits_launch_limit),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = currentLaunch?.let { "Active today: $it opens/day" }
                            ?: "No daily launch cap set",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = launchLimitEnabled,
                    onCheckedChange = { launchLimitEnabled = it },
                    modifier = Modifier.testTag("toggle_launch_limit_switch")
                )
            }

            if (launchLimitEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = { launchLimit = (launchLimit - 1).coerceAtLeast(1) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("decrease_launch_limit_btn")
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = "Decrease launch limit by 1")
                    }
                    Text(
                        text = "$launchLimit ${if (launchLimit == 1) "open" else "opens"}/day",
                        style = MonoMetricStyle.copy(fontSize = 20.sp),
                        modifier = Modifier.testTag("draft_launch_limit_text")
                    )
                    FilledTonalIconButton(
                        onClick = { launchLimit = (launchLimit + 1).coerceAtMost(100) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("increase_launch_limit_btn")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Increase launch limit by 1")
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(1, 3, 5, 10, 15, 25).forEach { preset ->
                        FilterChip(
                            selected = launchLimit == preset,
                            onClick = { launchLimit = preset },
                            label = { Text("${preset}x") }
                        )
                    }
                }
            }

            // Live Asymmetric Enforcement Preview Banner
            if (willTriggerLockoutNow) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = extendedColors.lockedContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = extendedColors.onLockedContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.limits_trigger_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = extendedColors.onLockedContainer
                        )
                    }
                }
            } else if (hasLooserChange) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = extendedColors.warningContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = extendedColors.onWarningContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.limits_next_day_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = extendedColors.onWarningContainer
                        )
                    }
                }
            } else if (hasStricterChange) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.limits_immediate_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("cancel_limit_sheet_btn")
                ) {
                    Text(stringResource(R.string.limits_cancel))
                }
                Button(
                    onClick = { onSave(draftTime, draftLaunch) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("save_limit_sheet_btn")
                ) {
                    Text(
                        if (hasLooserChange && !hasStricterChange) {
                            "Queue for 00:00"
                        } else {
                            stringResource(R.string.limits_save)
                        }
                    )
                }
            }
        }
    }
}
