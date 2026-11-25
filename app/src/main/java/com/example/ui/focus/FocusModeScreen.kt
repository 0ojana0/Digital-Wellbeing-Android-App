package com.example.ui.focus

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.FocusModeEntity
import com.example.ui.FocusModeUiItem
import com.example.ui.WellbeingUiState
import com.example.ui.common.StatusPill
import com.example.ui.common.formatDayMaskShort
import com.example.ui.common.formatMinuteOfDay
import com.example.ui.theme.LocalWellbeingColors
import com.example.ui.theme.MonoBadgeStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FocusModeScreen(
    uiState: WellbeingUiState,
    formatCountdown: (Long) -> String,
    onCreateFocusMode: () -> Unit,
    onEditFocusMode: (FocusModeEntity) -> Unit,
    onToggleFocusMode: (Long, Boolean) -> Unit,
    onDeleteFocusMode: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "focus_rule_banner") {
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
                            text = stringResource(R.string.focus_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (uiState.focusModes.isEmpty()) {
                item(key = "focus_empty_state") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DoNotDisturbOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.focus_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.focus_empty_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(items = uiState.focusModes, key = { it.entity.id }) { item ->
                    FocusModeCard(
                        item = item,
                        formatCountdown = formatCountdown,
                        onEdit = { onEditFocusMode(item.entity) },
                        onToggle = { enabled -> onToggleFocusMode(item.entity.id, enabled) },
                        onDelete = { onDeleteFocusMode(item.entity.id) }
                    )
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onCreateFocusMode,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.focus_add)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag("add_focus_mode_fab")
        )
    }
}

@Composable
private fun FocusModeCard(
    item: FocusModeUiItem,
    formatCountdown: (Long) -> String,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val extendedColors = LocalWellbeingColors.current
    val mode = item.entity

    val scheduleSummary = if (mode.scheduleType == FocusModeEntity.TYPE_DAILY) {
        "${formatMinuteOfDay(mode.startMinuteOfDay)} – ${formatMinuteOfDay(mode.endMinuteOfDay)}  •  ${formatDayMaskShort(mode.daysOfWeekMask)}"
    } else {
        val timeFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        "Once: ${timeFmt.format(Date(mode.oneTimeStartEpochMs))} – ${timeFmt.format(Date(mode.oneTimeEndEpochMs))}"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (item.isActiveNow) {
                extendedColors.lockedContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("focus_mode_card_${mode.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = mode.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.isActiveNow) {
                            StatusPill(
                                text = stringResource(R.string.focus_active_badge),
                                containerColor = extendedColors.lockedContainer,
                                contentColor = extendedColors.onLockedContainer,
                                icon = Icons.Filled.Lock
                            )
                        } else if (mode.isEnabled) {
                            StatusPill(
                                text = stringResource(R.string.focus_scheduled_badge),
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        } else {
                            StatusPill(
                                text = stringResource(R.string.focus_paused_badge),
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = scheduleSummary,
                        style = MonoBadgeStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (item.isActiveNow) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Focus Mode active and locked",
                        tint = extendedColors.locked,
                        modifier = Modifier.padding(8.dp)
                    )
                } else {
                    Switch(
                        checked = mode.isEnabled,
                        onCheckedChange = onToggle,
                        modifier = Modifier.testTag("focus_toggle_${mode.id}")
                    )
                }
            }

            if (item.isActiveNow) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Ends in ${formatCountdown(item.remainingMillis)} — editing and canceling locked",
                    style = MonoBadgeStyle,
                    color = extendedColors.locked
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(10.dp))

            val appsPreview = if (item.resolvedAppLabels.size <= 4) {
                item.resolvedAppLabels.joinToString(", ")
            } else {
                item.resolvedAppLabels.take(4).joinToString(", ") +
                    " +${item.resolvedAppLabels.size - 4} more"
            }
            Text(
                text = "Blocks ${item.resolvedAppLabels.size} ${if (item.resolvedAppLabels.size == 1) "app" else "apps"}: $appsPreview",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!item.isActiveNow) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("focus_edit_${mode.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Edit ${mode.name}"
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("focus_delete_${mode.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete ${mode.name}",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
