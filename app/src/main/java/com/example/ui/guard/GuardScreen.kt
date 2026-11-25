package com.example.ui.guard

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.WellbeingUiState
import com.example.ui.common.StatusPill
import com.example.ui.theme.LocalWellbeingColors

@Composable
fun GuardScreen(
    uiState: WellbeingUiState,
    onOpenUsageAccess: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onEnrollDeviceAdmin: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onToggleSettingsShield: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val perms = uiState.permissionsState
    val extendedColors = LocalWellbeingColors.current
    val settingsShieldEnabled = uiState.enforcementState?.settingsShieldEnabled ?: true
    val shieldLocked = uiState.isSelfLocked || uiState.hasAnyActiveFocusMode

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
            item(key = "guard_summary_card") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (perms.coreProtectionReady) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            extendedColors.warningContainer
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (perms.coreProtectionReady) Icons.Filled.GppGood else Icons.Filled.Warning,
                            contentDescription = null,
                            tint = if (perms.coreProtectionReady) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                extendedColors.onWarningContainer
                            },
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (perms.coreProtectionReady) {
                                    "Strict Enforcement Ready (${perms.grantedCount}/5)"
                                } else {
                                    "Setup Required (${perms.grantedCount}/5)"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = if (perms.coreProtectionReady) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    extendedColors.onWarningContainer
                                }
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.guard_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (perms.coreProtectionReady) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    extendedColors.onWarningContainer
                                }
                            )
                        }
                    }
                }
            }

            if (uiState.tamperDetected && !uiState.tamperNote.isNullOrBlank()) {
                item(key = "clock_tamper_alert") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = extendedColors.lockedContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = extendedColors.onLockedContainer
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Clock Tampering Detected",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = extendedColors.onLockedContainer
                                )
                                Text(
                                    text = uiState.tamperNote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = extendedColors.onLockedContainer
                                )
                            }
                        }
                    }
                }
            }

            item(key = "permissions_section_title") {
                Text(
                    text = "System Permissions",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            item(key = "perm_usage") {
                PermissionRowCard(
                    icon = Icons.Filled.QueryStats,
                    title = "Usage Access",
                    description = "Reads real Android screen time, device unlocks, and app launches.",
                    isGranted = perms.usageAccessGranted,
                    actionLabel = "Grant",
                    testTag = "guard_usage_access_btn",
                    onAction = onOpenUsageAccess
                )
            }

            item(key = "perm_accessibility") {
                PermissionRowCard(
                    icon = Icons.Filled.Security,
                    title = "Accessibility Blocker Service",
                    description = "Instant app blocking, 1s passive video cutoff, split-screen detection, and Settings Shield.",
                    isGranted = perms.accessibilityEnabled,
                    actionLabel = "Enable",
                    testTag = "guard_accessibility_btn",
                    onAction = onOpenAccessibilitySettings
                )
            }

            item(key = "perm_device_admin") {
                PermissionRowCard(
                    icon = Icons.Filled.AdminPanelSettings,
                    title = "Device Admin Uninstall Shield",
                    description = "Blocks direct uninstallation of Wellbeing Lock from launcher or Settings.",
                    isGranted = perms.deviceAdminActive,
                    actionLabel = "Activate",
                    testTag = "guard_device_admin_btn",
                    onAction = onEnrollDeviceAdmin
                )
            }

            item(key = "perm_notifications") {
                PermissionRowCard(
                    icon = Icons.Filled.Notifications,
                    title = "Guard Status Notification",
                    description = "Keeps the real-time timer monitor alive in the background.",
                    isGranted = perms.notificationsGranted,
                    actionLabel = "Allow",
                    testTag = "guard_notifications_btn",
                    onAction = onRequestNotifications
                )
            }

            item(key = "perm_battery") {
                PermissionRowCard(
                    icon = Icons.Filled.BatteryChargingFull,
                    title = "Unrestricted Battery",
                    description = "Prevents Android battery optimization from stopping midnight reset & timer checks.",
                    isGranted = perms.ignoringBatteryOptimizations,
                    actionLabel = "Configure",
                    testTag = "guard_battery_btn",
                    onAction = onOpenBatteryOptimization
                )
            }

            item(key = "defenses_section_title") {
                Text(
                    text = "Active Anti-Bypass Defenses",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            item(key = "defense_settings_shield") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.guard_settings_shield),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Blocks Force Stop, Clear Storage, Uninstall, Accessibility toggle, Device Admin revoke, and Date & Time settings while rules are active.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = settingsShieldEnabled,
                            enabled = !shieldLocked || !settingsShieldEnabled,
                            onCheckedChange = onToggleSettingsShield,
                            modifier = Modifier.testTag("settings_shield_switch")
                        )
                    }
                }
            }

            item(key = "defense_rules_list") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DefenseFeatureItem(
                            icon = Icons.Filled.Update,
                            title = stringResource(R.string.guard_clock_tamper),
                            description = "Anchors 00:00 reset to monotonic uptime so advancing device clock or timezone cannot unlock early.",
                            statusText = if (uiState.tamperDetected) "Tamper Held" else "Verified"
                        )
                        DefenseFeatureItem(
                            icon = Icons.Filled.Splitscreen,
                            title = stringResource(R.string.guard_split_screen),
                            description = "Inspects all interactive windows to block split-screen, freeform, PiP, widget, and notification launches.",
                            statusText = if (perms.accessibilityEnabled) "Active" else "Needs Service"
                        )
                        DefenseFeatureItem(
                            icon = Icons.Filled.Lock,
                            title = stringResource(R.string.guard_next_day_rule),
                            description = "Decreasing a limit applies immediately; increasing or removing a limit waits until 00:00 tomorrow.",
                            statusText = "Enforced"
                        )
                        DefenseFeatureItem(
                            icon = Icons.Filled.GppGood,
                            title = stringResource(R.string.guard_self_lock_rule),
                            description = "Hitting any app's daily time or launch cap locks both that app and Wellbeing Lock until midnight.",
                            statusText = "Always On"
                        )
                    }
                }
            }

            item(key = "os_transparency_note") {
                Text(
                    text = "OS Boundary Note: Wellbeing Lock blocks all standard user-space bypass paths on Android. Hardware-level Safe Mode reboots and USB ADB shell commands remain system-reserved boundaries on non-rooted Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun PermissionRowCard(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    actionLabel: String,
    testTag: String,
    onAction: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (isGranted) {
                StatusPill(
                    text = "Active",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = Icons.Filled.CheckCircle
                )
            } else {
                FilledTonalButton(
                    onClick = onAction,
                    modifier = Modifier.testTag(testTag)
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun DefenseFeatureItem(
    icon: ImageVector,
    title: String,
    description: String,
    statusText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusPill(
                    text = statusText,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
