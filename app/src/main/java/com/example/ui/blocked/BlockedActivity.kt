package com.example.ui.blocked

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.HourglassDisabled
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.WellbeingLockApplication
import com.example.data.usage.BlockReasonType
import com.example.ui.theme.LocalWellbeingColors
import com.example.ui.theme.MonoDisplayTimerStyle
import com.example.ui.theme.WellbeingLockTheme
import kotlinx.coroutines.delay

/**
 * Full-screen uncompromising block wall shown whenever a user attempts to open a limited app,
 * Focus-blocked app, or shielded Android Settings bypass page.
 * Contains ZERO snooze, override, or unlock buttons.
 */
class BlockedActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateHomeAndFinish()
            }
        })

        renderFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderFromIntent(intent)
    }

    private fun renderFromIntent(currentIntent: Intent) {
        val appLabel = currentIntent.getStringExtra(EXTRA_APP_LABEL).orEmpty().ifBlank { "Blocked App" }
        val headline = currentIntent.getStringExtra(EXTRA_HEADLINE).orEmpty()
            .ifBlank { getString(R.string.blocked_app_title) }
        val detail = currentIntent.getStringExtra(EXTRA_DETAIL).orEmpty()
        val reasonName = currentIntent.getStringExtra(EXTRA_REASON_TYPE)
        val reasonType = runCatching { BlockReasonType.valueOf(reasonName ?: "") }
            .getOrDefault(BlockReasonType.TIME_LIMIT)
        val unlockEpochMs = currentIntent.getLongExtra(EXTRA_UNLOCK_EPOCH_MS, 0L)

        val clockGuard = WellbeingLockApplication.from(this).container.clockTamperGuard

        setContent {
            WellbeingLockTheme {
                BlockedScreenContent(
                    appLabel = appLabel,
                    headline = headline,
                    detail = detail,
                    reasonType = reasonType,
                    unlockEpochMs = unlockEpochMs,
                    formatCountdown = clockGuard::formatCountdown,
                    onReturnHome = ::navigateHomeAndFinish
                )
            }
        }
    }

    private fun navigateHomeAndFinish() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { startActivity(homeIntent) }
        finish()
    }

    companion object {
        private const val EXTRA_APP_LABEL = "extra_app_label"
        private const val EXTRA_HEADLINE = "extra_headline"
        private const val EXTRA_DETAIL = "extra_detail"
        private const val EXTRA_REASON_TYPE = "extra_reason_type"
        private const val EXTRA_UNLOCK_EPOCH_MS = "extra_unlock_epoch_ms"

        fun createIntent(
            context: Context,
            appLabel: String,
            headline: String,
            detail: String,
            reasonType: BlockReasonType,
            unlockEpochMs: Long
        ): Intent {
            return Intent(context, BlockedActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                putExtra(EXTRA_APP_LABEL, appLabel)
                putExtra(EXTRA_HEADLINE, headline)
                putExtra(EXTRA_DETAIL, detail)
                putExtra(EXTRA_REASON_TYPE, reasonType.name)
                putExtra(EXTRA_UNLOCK_EPOCH_MS, unlockEpochMs)
            }
        }
    }
}

@Composable
private fun BlockedScreenContent(
    appLabel: String,
    headline: String,
    detail: String,
    reasonType: BlockReasonType,
    unlockEpochMs: Long,
    formatCountdown: (Long) -> String,
    onReturnHome: () -> Unit
) {
    val extendedColors = LocalWellbeingColors.current
    var remainingMs by remember(unlockEpochMs) {
        mutableLongStateOf((unlockEpochMs - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    LaunchedEffect(unlockEpochMs) {
        while (true) {
            remainingMs = (unlockEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(1_000L)
        }
    }

    val icon = when (reasonType) {
        BlockReasonType.TIME_LIMIT -> Icons.Filled.HourglassDisabled
        BlockReasonType.LAUNCH_LIMIT -> Icons.Filled.Block
        BlockReasonType.FOCUS_MODE -> Icons.Filled.DoNotDisturbOn
        BlockReasonType.ANTI_BYPASS_SHIELD -> Icons.Filled.GppGood
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = extendedColors.lockedContainer,
                    contentColor = extendedColors.onLockedContainer,
                    modifier = Modifier.size(80.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = headline,
                        modifier = Modifier.padding(20.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = appLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = extendedColors.locked,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = headline,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                if (unlockEpochMs > 0L) {
                    Spacer(modifier = Modifier.height(28.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp, horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (reasonType == BlockReasonType.FOCUS_MODE) {
                                    "FOCUS ENDS IN"
                                } else {
                                    "UNLOCKS AT 00:00"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = formatCountdown(remainingMs),
                                style = MonoDisplayTimerStyle,
                                color = extendedColors.locked,
                                modifier = Modifier.testTag("blocked_countdown_text")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.blocked_no_unlock_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(28.dp))

                Button(
                    onClick = onReturnHome,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("blocked_return_home_button")
                ) {
                    Text(text = stringResource(R.string.blocked_return_home))
                }
            }
        }
    }
}
