package com.example.guard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.WellbeingLockApplication
import com.example.data.usage.BlockDecision
import com.example.data.usage.BlockReasonType
import com.example.ui.blocked.BlockedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Core real-time enforcement & anti-bypass AccessibilityService:
 * 1. Detects foreground app transitions (including deep links, notifications, widgets, share sheets)
 *    with debounced launch counting.
 * 2. Scans ALL visible windows on TYPE_WINDOWS_CHANGED to catch split-screen, freeform, and PiP bypasses.
 * 3. Runs a 1-second active foreground ticker so passive video playback triggers exact-second cutoffs.
 * 4. Shields Android Settings & Package Installer against force-stop, clear-data, uninstall,
 *    Accessibility revocation, Device Admin removal, Usage Access revocation, and Date & Time tampering.
 */
class StrictGuardAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeTickerJob: Job? = null

    @Volatile
    private var activeForegroundPackage: String? = null

    @Volatile
    private var lastNonTransientPackage: String? = null

    @Volatile
    private var lastBlockTriggerMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        StrictGuardForegroundService.startIfPermitted(this)
        startOneSecondActiveTicker()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (!pkg.isNullOrBlank()) {
                    handleForegroundPackageEvent(pkg)
                }
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                inspectAllVisibleWindows()
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg != null && isSettingsOrInstallerPackage(pkg)) {
                    inspectSettingsBypassTree(pkg)
                }
            }
        }
    }

    override fun onInterrupt() {
        // Keep running cleanly
    }

    override fun onDestroy() {
        activeTickerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleForegroundPackageEvent(pkg: String) {
        if (pkg == packageName) {
            activeForegroundPackage = pkg
            lastNonTransientPackage = pkg
            return
        }

        if (isIgnoredSystemTransient(pkg)) {
            return
        }

        // Anti-bypass Settings & Package Installer inspection
        if (isSettingsOrInstallerPackage(pkg)) {
            activeForegroundPackage = pkg
            inspectSettingsBypassTree(pkg)
            return
        }

        val isNewLaunch = lastNonTransientPackage != pkg
        activeForegroundPackage = pkg
        lastNonTransientPackage = pkg

        serviceScope.launch {
            val engine = WellbeingLockApplication.from(this@StrictGuardAccessibilityService)
                .container.enforcementEngine
            val decision = engine.evaluatePackageAccess(
                packageName = pkg,
                isNewLaunch = isNewLaunch,
                addActiveSeconds = 0L
            )
            if (decision.isBlocked) {
                ejectAndShowBlockedScreen(decision)
            }
        }
    }

    /**
     * Multi-window / Split-screen / Picture-in-Picture guard: inspects every visible interactive
     * window on screen so opening a blocked app in split-screen alongside an allowed app is caught.
     */
    private fun inspectAllVisibleWindows() {
        val visiblePackages = LinkedHashSet<String>()
        try {
            val currentWindows = windows ?: return
            for (window in currentWindows) {
                val root = window.root ?: continue
                val pkg = root.packageName?.toString()
                if (!pkg.isNullOrBlank() && pkg != packageName && !isIgnoredSystemTransient(pkg)) {
                    visiblePackages.add(pkg)
                }
            }
        } catch (_: Exception) {
            return
        }

        if (visiblePackages.isEmpty()) return

        serviceScope.launch {
            val engine = WellbeingLockApplication.from(this@StrictGuardAccessibilityService)
                .container.enforcementEngine
            for (pkg in visiblePackages) {
                if (isSettingsOrInstallerPackage(pkg)) {
                    inspectSettingsBypassTree(pkg)
                    continue
                }
                val decision = engine.evaluatePackageAccess(
                    packageName = pkg,
                    isNewLaunch = false,
                    addActiveSeconds = 0L
                )
                if (decision.isBlocked) {
                    ejectAndShowBlockedScreen(decision)
                    break
                }
            }
        }
    }

    /**
     * 1-second active ticker guarantees exact-second time limit cutoff even when the user watches
     * video passively without touching the screen.
     */
    private fun startOneSecondActiveTicker() {
        activeTickerJob?.cancel()
        activeTickerJob = serviceScope.launch {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val engine = WellbeingLockApplication.from(this@StrictGuardAccessibilityService)
                .container.enforcementEngine

            while (isActive) {
                delay(1_000L)
                val isInteractive = powerManager?.isInteractive ?: true
                if (!isInteractive) {
                    lastNonTransientPackage = null
                    continue
                }
                val currentPkg = activeForegroundPackage ?: continue
                if (currentPkg == packageName || isIgnoredSystemTransient(currentPkg) || isSettingsOrInstallerPackage(currentPkg)) {
                    continue
                }
                val decision = engine.evaluatePackageAccess(
                    packageName = currentPkg,
                    isNewLaunch = false,
                    addActiveSeconds = 1L
                )
                if (decision.isBlocked) {
                    ejectAndShowBlockedScreen(decision)
                }
            }
        }
    }

    /**
     * Walks visible Settings / Package Installer UI text to detect & block bypass attempts:
     * - App Info / Force Stop / Clear Storage / Uninstall of Wellbeing Lock
     * - Accessibility Service toggle for Wellbeing Lock
     * - Device Admin deactivation for Wellbeing Lock
     * - Usage Access revocation for Wellbeing Lock
     * - Date & Time / Timezone manipulation screens
     */
    private fun inspectSettingsBypassTree(settingsPkg: String) {
        val rootNode = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return

        val collectedText = StringBuilder()
        collectNodeText(rootNode, collectedText, maxNodes = 120)
        val screenText = collectedText.toString().lowercase()
        if (screenText.isBlank()) return

        val mentionsOurApp = screenText.contains("wellbeing lock") ||
            screenText.contains(packageName.lowercase())

        val isDateTimeScreen = DATE_TIME_KEYWORDS.any { screenText.contains(it) }
        val isDangerousAppControl = mentionsOurApp && DANGEROUS_SETTINGS_KEYWORDS.any { screenText.contains(it) }
        val isPackageInstallerOnUs = settingsPkg.contains("packageinstaller") && mentionsOurApp

        if (!isDateTimeScreen && !isDangerousAppControl && !isPackageInstallerOnUs) {
            return
        }

        val reasonDetail = when {
            isDateTimeScreen ->
                "Changing system Date & Time or Timezone is blocked while strict rules are active."
            isPackageInstallerOnUs ->
                "Uninstalling Wellbeing Lock is blocked by the Anti-Bypass Shield."
            else ->
                "Modifying Wellbeing Lock permissions, Force Stop, Clear Data, Accessibility, or Device Admin is blocked."
        }

        serviceScope.launch {
            val engine = WellbeingLockApplication.from(this@StrictGuardAccessibilityService)
                .container.enforcementEngine
            val (shouldShield, unlockEpochMs) = engine.shouldShieldSettingsNow()
            if (shouldShield) {
                ejectAndShowBlockedScreen(
                    BlockDecision(
                        isBlocked = true,
                        reasonType = BlockReasonType.ANTI_BYPASS_SHIELD,
                        targetPackage = settingsPkg,
                        targetAppLabel = "System Settings",
                        headline = "Anti-Bypass Shield Triggered",
                        detail = reasonDetail,
                        unlockEpochMs = unlockEpochMs
                    )
                )
            }
        }
    }

    private fun collectNodeText(
        node: AccessibilityNodeInfo?,
        out: StringBuilder,
        maxNodes: Int,
        counter: IntArray = intArrayOf(0)
    ) {
        if (node == null || counter[0] >= maxNodes) return
        counter[0]++
        node.text?.let {
            if (it.isNotBlank()) out.append(it).append(' ')
        }
        node.contentDescription?.let {
            if (it.isNotBlank()) out.append(it).append(' ')
        }
        val count = node.childCount
        for (i in 0 until count) {
            if (counter[0] >= maxNodes) break
            collectNodeText(node.getChild(i), out, maxNodes, counter)
        }
    }

    private fun ejectAndShowBlockedScreen(decision: BlockDecision) {
        val now = System.currentTimeMillis()
        if (now - lastBlockTriggerMs < 900L) return
        lastBlockTriggerMs = now
        activeForegroundPackage = packageName
        lastNonTransientPackage = packageName

        // Immediately exit the blocked app (including split-screen / PiP)
        performGlobalAction(GLOBAL_ACTION_HOME)

        val intent = BlockedActivity.createIntent(
            context = this,
            appLabel = decision.targetAppLabel,
            headline = decision.headline,
            detail = decision.detail,
            reasonType = decision.reasonType ?: BlockReasonType.TIME_LIMIT,
            unlockEpochMs = decision.unlockEpochMs
        )
        runCatching { startActivity(intent) }
    }

    private fun isSettingsOrInstallerPackage(pkg: String): Boolean {
        return pkg == "com.android.settings" ||
            pkg == "com.google.android.packageinstaller" ||
            pkg == "com.android.packageinstaller" ||
            pkg == "com.samsung.android.settings" ||
            pkg == "com.miui.securitycenter" ||
            pkg == "com.coloros.safecenter"
    }

    private fun isIgnoredSystemTransient(pkg: String): Boolean {
        return pkg == "com.android.systemui" ||
            pkg == "android" ||
            pkg.contains("inputmethod") ||
            pkg.contains("keyboard") ||
            pkg.contains("gboard")
    }

    companion object {
        private val DATE_TIME_KEYWORDS = listOf(
            "set time automatically",
            "date & time",
            "date and time",
            "automatic date",
            "select time zone",
            "use network-provided time",
            "set date",
            "set time"
        )

        private val DANGEROUS_SETTINGS_KEYWORDS = listOf(
            "force stop",
            "uninstall",
            "clear storage",
            "clear data",
            "disable",
            "deactivate",
            "stop",
            "use wellbeing lock",
            "permit usage access",
            "device admin",
            "accessibility"
        )
    }
}
