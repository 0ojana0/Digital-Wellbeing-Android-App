package com.example

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.FocusModeEntity
import com.example.guard.GuardPermissionsHelper
import com.example.ui.WellbeingTab
import com.example.ui.WellbeingViewModel
import com.example.ui.blocked.SelfLockoutScreen
import com.example.ui.dashboard.DashboardScreen
import com.example.ui.focus.FocusModeEditorSheet
import com.example.ui.focus.FocusModeScreen
import com.example.ui.guard.GuardScreen
import com.example.ui.limits.AppLimitEditorSheet
import com.example.ui.limits.LimitsScreen
import com.example.ui.theme.WellbeingLockTheme

private data class AppLimitEditorTarget(
    val packageName: String,
    val appLabel: String,
    val todayUsageMs: Long,
    val todayLaunches: Int
)

private data class NavDestinationItem(
    val tab: WellbeingTab,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {

    private val viewModel: WellbeingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WellbeingLockTheme {
                WellbeingLockAppRoot(
                    viewModel = viewModel,
                    onExitToHome = ::navigateToHome
                )
            }
        }
    }

    private fun navigateToHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { startActivity(homeIntent) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WellbeingLockAppRoot(
    viewModel: WellbeingViewModel,
    onExitToHome: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clockGuard = remember { WellbeingLockApplication.from(context).container.clockTamperGuard }

    var activeLimitEditor by remember { mutableStateOf<AppLimitEditorTarget?>(null) }
    var showFocusEditor by remember { mutableStateOf(false) }
    var editingFocusMode by remember { mutableStateOf<FocusModeEntity?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { viewModel.refreshAllData() }
    )

    val deviceAdminLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { viewModel.refreshAllData() }
    )

    // Refresh permissions & real UsageStats whenever returning from Android System Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAllData()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // STRICT DUAL SELF-LOCKOUT GATE:
    // Whenever ANY app hits its daily time or launch limit, Wellbeing Lock itself is strictly locked
    // until 00:00 midnight with ZERO unlock, snooze, or settings buttons.
    if (uiState.isSelfLocked) {
        BackHandler(enabled = true) { onExitToHome() }
        SelfLockoutScreen(
            formattedCountdown = uiState.formattedMidnightCountdown,
            breachReasonSummary = uiState.enforcementState?.selfLockReasonSummary,
            tamperDetected = uiState.tamperDetected,
            tamperNote = uiState.tamperNote,
            onExitToHome = onExitToHome
        )
        return
    }

    // Back press on secondary tabs returns cleanly to Dashboard
    BackHandler(enabled = uiState.selectedTab != WellbeingTab.DASHBOARD) {
        viewModel.selectTab(WellbeingTab.DASHBOARD)
    }

    val navItems = remember {
        listOf(
            NavDestinationItem(
                tab = WellbeingTab.DASHBOARD,
                labelRes = R.string.nav_dashboard,
                selectedIcon = Icons.Filled.Dashboard,
                unselectedIcon = Icons.Outlined.Dashboard,
                testTag = "nav_tab_dashboard"
            ),
            NavDestinationItem(
                tab = WellbeingTab.LIMITS,
                labelRes = R.string.nav_limits,
                selectedIcon = Icons.Filled.HourglassTop,
                unselectedIcon = Icons.Outlined.HourglassEmpty,
                testTag = "nav_tab_limits"
            ),
            NavDestinationItem(
                tab = WellbeingTab.FOCUS,
                labelRes = R.string.nav_focus,
                selectedIcon = Icons.Filled.DoNotDisturbOn,
                unselectedIcon = Icons.Outlined.DoNotDisturbOn,
                testTag = "nav_tab_focus"
            ),
            NavDestinationItem(
                tab = WellbeingTab.GUARD,
                labelRes = R.string.nav_guard,
                selectedIcon = Icons.Filled.Shield,
                unselectedIcon = Icons.Outlined.Shield,
                testTag = "nav_tab_guard"
            )
        )
    }

    val topTitle = when (uiState.selectedTab) {
        WellbeingTab.DASHBOARD -> stringResource(R.string.dashboard_title)
        WellbeingTab.LIMITS -> stringResource(R.string.limits_title)
        WellbeingTab.FOCUS -> stringResource(R.string.focus_title)
        WellbeingTab.GUARD -> stringResource(R.string.guard_title)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = topTitle) },
                    actions = {
                        IconButton(
                            onClick = { viewModel.refreshAllData() },
                            modifier = Modifier.testTag("refresh_wellbeing_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh usage data"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar {
                        navItems.forEach { item ->
                            val selected = uiState.selectedTab == item.tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(item.tab) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(modifier = Modifier.fillMaxHeight()) {
                        navItems.forEach { item ->
                            val selected = uiState.selectedTab == item.tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(item.tab) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (uiState.selectedTab) {
                        WellbeingTab.DASHBOARD -> {
                            DashboardScreen(
                                uiState = uiState,
                                onSelectDay = viewModel::selectDayIndex,
                                onGrantUsageAccess = {
                                    GuardPermissionsHelper.launchSafely(
                                        context,
                                        GuardPermissionsHelper.usageAccessIntent(context),
                                        GuardPermissionsHelper.usageAccessFallbackIntent()
                                    )
                                },
                                onOpenGuardTab = { viewModel.selectTab(WellbeingTab.GUARD) },
                                onOpenAppLimitEditor = { pkg, label, usageMs, launches ->
                                    activeLimitEditor = AppLimitEditorTarget(pkg, label, usageMs, launches)
                                }
                            )
                        }

                        WellbeingTab.LIMITS -> {
                            LimitsScreen(
                                uiState = uiState,
                                onSearchQueryChange = viewModel::updateSearchQuery,
                                onShowOnlyLimitedChange = viewModel::setShowOnlyLimited,
                                onEditAppLimit = { pkg, label, usageMs, launches ->
                                    activeLimitEditor = AppLimitEditorTarget(pkg, label, usageMs, launches)
                                },
                                onCancelPendingChange = viewModel::cancelPendingLimitChange
                            )
                        }

                        WellbeingTab.FOCUS -> {
                            FocusModeScreen(
                                uiState = uiState,
                                formatCountdown = clockGuard::formatCountdown,
                                onCreateFocusMode = {
                                    editingFocusMode = null
                                    showFocusEditor = true
                                },
                                onEditFocusMode = { mode ->
                                    editingFocusMode = mode
                                    showFocusEditor = true
                                },
                                onToggleFocusMode = viewModel::toggleFocusMode,
                                onDeleteFocusMode = viewModel::deleteFocusMode
                            )
                        }

                        WellbeingTab.GUARD -> {
                            GuardScreen(
                                uiState = uiState,
                                onOpenUsageAccess = {
                                    GuardPermissionsHelper.launchSafely(
                                        context,
                                        GuardPermissionsHelper.usageAccessIntent(context),
                                        GuardPermissionsHelper.usageAccessFallbackIntent()
                                    )
                                },
                                onOpenAccessibilitySettings = {
                                    GuardPermissionsHelper.launchSafely(
                                        context,
                                        GuardPermissionsHelper.accessibilitySettingsIntent()
                                    )
                                },
                                onEnrollDeviceAdmin = {
                                    deviceAdminLauncher.launch(
                                        GuardPermissionsHelper.deviceAdminEnrollmentIntent(context)
                                    )
                                },
                                onRequestNotifications = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                onOpenBatteryOptimization = {
                                    GuardPermissionsHelper.launchSafely(
                                        context,
                                        GuardPermissionsHelper.batteryOptimizationSettingsIntent()
                                    )
                                },
                                onToggleSettingsShield = viewModel::setSettingsShieldEnabled
                            )
                        }
                    }
                }
            }
        }
    }

    // App Limit Modal Bottom Sheet
    val limitTarget = activeLimitEditor
    if (limitTarget != null) {
        AppLimitEditorSheet(
            packageName = limitTarget.packageName,
            appLabel = limitTarget.appLabel,
            todayUsageMs = limitTarget.todayUsageMs,
            todayLaunches = limitTarget.todayLaunches,
            existingLimit = uiState.limitsByPackage[limitTarget.packageName],
            onDismiss = { activeLimitEditor = null },
            onSave = { timeMins, launchCap ->
                viewModel.saveAppLimit(
                    packageName = limitTarget.packageName,
                    appLabel = limitTarget.appLabel,
                    timeLimitMinutes = timeMins,
                    launchLimit = launchCap
                )
                activeLimitEditor = null
            }
        )
    }

    // Focus Mode Modal Bottom Sheet
    if (showFocusEditor) {
        FocusModeEditorSheet(
            existingMode = editingFocusMode,
            installedApps = uiState.installedApps,
            onDismiss = {
                showFocusEditor = false
                editingFocusMode = null
            },
            onSave = { mode ->
                viewModel.saveFocusMode(mode)
                showFocusEditor = false
                editingFocusMode = null
            }
        )
    }
}
