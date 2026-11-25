package com.example.ui.focus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.example.R
import com.example.data.local.FocusModeEntity
import com.example.data.usage.InstalledAppInfo
import com.example.ui.common.AppIconImage
import com.example.ui.common.formatMinuteOfDay
import com.example.ui.theme.LocalWellbeingColors
import com.example.ui.theme.MonoMetricStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FocusModeEditorSheet(
    existingMode: FocusModeEntity?,
    installedApps: List<InstalledAppInfo>,
    onDismiss: () -> Unit,
    onSave: (FocusModeEntity) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val extendedColors = LocalWellbeingColors.current

    var name by remember(existingMode) {
        mutableStateOf(existingMode?.name ?: "Deep Focus")
    }
    var scheduleType by remember(existingMode) {
        mutableStateOf(existingMode?.scheduleType ?: FocusModeEntity.TYPE_DAILY)
    }
    var daysMask by remember(existingMode) {
        mutableIntStateOf(existingMode?.daysOfWeekMask ?: 0b0011111) // Mon-Fri default
    }
    var startMinute by remember(existingMode) {
        mutableIntStateOf(existingMode?.startMinuteOfDay ?: 9 * 60)
    }
    var endMinute by remember(existingMode) {
        mutableIntStateOf(existingMode?.endMinuteOfDay ?: 17 * 60)
    }
    var oneTimeDurationMinutes by remember(existingMode) {
        val existingDur = if (existingMode != null && existingMode.scheduleType == FocusModeEntity.TYPE_ONE_TIME) {
            ((existingMode.oneTimeEndEpochMs - existingMode.oneTimeStartEpochMs) / 60_000L).toInt()
                .coerceIn(15, 720)
        } else {
            60
        }
        mutableIntStateOf(existingDur)
    }
    var oneTimeDelayMinutes by remember { mutableIntStateOf(0) } // 0 = start immediately

    var selectedPackages by remember(existingMode) {
        mutableStateOf(existingMode?.targetPackageSet() ?: emptySet())
    }
    var appFilter by remember { mutableStateOf("") }

    val filteredApps = remember(installedApps, appFilter) {
        val q = appFilter.trim().lowercase()
        if (q.isEmpty()) installedApps
        else installedApps.filter { it.label.lowercase().contains(q) }
    }

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
            Text(
                text = if (existingMode == null) "New Focus Mode" else "Edit Focus Mode",
                style = MaterialTheme.typography.headlineSmall
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Focus mode name") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("focus_name_input")
            )

            // Daily Recurring vs One-Time Session
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = scheduleType == FocusModeEntity.TYPE_DAILY,
                    onClick = { scheduleType = FocusModeEntity.TYPE_DAILY },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    modifier = Modifier.testTag("focus_type_daily_btn")
                ) {
                    Text(stringResource(R.string.focus_type_daily))
                }
                SegmentedButton(
                    selected = scheduleType == FocusModeEntity.TYPE_ONE_TIME,
                    onClick = { scheduleType = FocusModeEntity.TYPE_ONE_TIME },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    modifier = Modifier.testTag("focus_type_once_btn")
                ) {
                    Text(stringResource(R.string.focus_type_once))
                }
            }

            if (scheduleType == FocusModeEntity.TYPE_DAILY) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TimeStepperBox(
                        label = "Start",
                        minuteOfDay = startMinute,
                        onDecrement = { startMinute = (startMinute - 15 + 1440) % 1440 },
                        onIncrement = { startMinute = (startMinute + 15) % 1440 },
                        modifier = Modifier.weight(1f)
                    )
                    TimeStepperBox(
                        label = "End",
                        minuteOfDay = endMinute,
                        onDecrement = { endMinute = (endMinute - 15 + 1440) % 1440 },
                        onIncrement = { endMinute = (endMinute + 15) % 1440 },
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = "Active days",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val dayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    dayLabels.forEachIndexed { idx, label ->
                        val bit = 1 shl idx
                        val selected = (daysMask and bit) != 0
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val next = if (selected) daysMask and bit.inv() else daysMask or bit
                                if (next != 0) daysMask = next
                            },
                            label = { Text(label) }
                        )
                    }
                }
            } else {
                Text(
                    text = "Duration",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(15, 30, 60, 120, 240, 480).forEach { mins ->
                        FilterChip(
                            selected = oneTimeDurationMinutes == mins,
                            onClick = { oneTimeDurationMinutes = mins },
                            label = {
                                Text(if (mins >= 60) "${mins / 60}h" else "${mins}m")
                            }
                        )
                    }
                }

                Text(
                    text = "Start timing",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0 to "Start now", 15 to "In 15m", 30 to "In 30m", 60 to "In 1h").forEach { (delayM, label) ->
                        FilterChip(
                            selected = oneTimeDelayMinutes == delayM,
                            onClick = { oneTimeDelayMinutes = delayM },
                            label = { Text(label) }
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = extendedColors.warningContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = extendedColors.onWarningContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Once active, this Focus Mode cannot be edited, paused, or canceled until it ends.",
                        style = MaterialTheme.typography.bodySmall,
                        color = extendedColors.onWarningContainer
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = "Apps to block (${selectedPackages.size} selected)",
                style = MaterialTheme.typography.titleSmall
            )

            OutlinedTextField(
                value = appFilter,
                onValueChange = { appFilter = it },
                placeholder = { Text("Filter apps…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(items = filteredApps, key = { it.packageName }) { app ->
                    val checked = app.packageName in selectedPackages
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedPackages = if (checked) {
                                    selectedPackages - app.packageName
                                } else {
                                    selectedPackages + app.packageName
                                }
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { isChecked ->
                                selectedPackages = if (isChecked) {
                                    selectedPackages + app.packageName
                                } else {
                                    selectedPackages - app.packageName
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        AppIconImage(drawable = app.icon, appLabel = app.label, size = 32.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text("Cancel")
                }
                Button(
                    enabled = name.isNotBlank() && selectedPackages.isNotEmpty(),
                    onClick = {
                        val now = System.currentTimeMillis()
                        val startEpoch = now + oneTimeDelayMinutes * 60_000L
                        val endEpoch = startEpoch + oneTimeDurationMinutes * 60_000L
                        val entity = FocusModeEntity(
                            id = existingMode?.id ?: 0L,
                            name = name.trim(),
                            targetPackagesCsv = selectedPackages.joinToString(","),
                            scheduleType = scheduleType,
                            daysOfWeekMask = daysMask,
                            startMinuteOfDay = startMinute,
                            endMinuteOfDay = endMinute,
                            oneTimeStartEpochMs = if (scheduleType == FocusModeEntity.TYPE_ONE_TIME) startEpoch else 0L,
                            oneTimeEndEpochMs = if (scheduleType == FocusModeEntity.TYPE_ONE_TIME) endEpoch else 0L,
                            isEnabled = true
                        )
                        onSave(entity)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("save_focus_mode_btn")
                ) {
                    Text(if (scheduleType == FocusModeEntity.TYPE_ONE_TIME && oneTimeDelayMinutes == 0) "Start & Lock" else "Save Mode")
                }
            }
        }
    }
}

@Composable
private fun TimeStepperBox(
    label: String,
    minuteOfDay: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilledTonalIconButton(
                    onClick = onDecrement,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = "Decrease $label time", modifier = Modifier.size(16.dp))
                }
                Text(
                    text = formatMinuteOfDay(minuteOfDay),
                    style = MonoMetricStyle
                )
                FilledTonalIconButton(
                    onClick = onIncrement,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Increase $label time", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
