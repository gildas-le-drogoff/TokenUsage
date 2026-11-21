package fr.gildasledrogoff.tokenusage.feature.settings.refresh

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.util.BatteryOptimizationHelper
import fr.gildasledrogoff.tokenusage.feature.settings.common.SettingsSubScreen

/** Refresh sub-screen: interval, background refresh (battery) and notifications. */
@Composable
fun RefreshSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: RefreshSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var batteryExempt by remember { mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) }
    LifecycleResumeEffect(Unit) {
        batteryExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }
    val launchBatteryIntent: (Intent) -> Unit = { intent ->
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(BatteryOptimizationHelper.openBatteryOptimizationSettingsIntent()) }
        }
    }

    SettingsSubScreen(title = stringResource(R.string.settings_menu_refresh), onNavigateBack = onNavigateBack) {
        NotificationsSection(
            enabled = uiState.notificationsEnabled,
            onToggle = { viewModel.setNotificationsEnabled(it) }
        )

        RefreshIntervalSection(
            currentMinutes = uiState.refreshIntervalMinutes,
            onIntervalChange = { viewModel.setRefreshInterval(it) },
            batteryExempt = batteryExempt,
            onRequestExemption = {
                viewModel.onBatteryChoiceMade()
                launchBatteryIntent(BatteryOptimizationHelper.requestIgnoreBatteryOptimizationsIntent(context))
            },
            onOpenBatterySettings = {
                viewModel.onBatteryChoiceMade()
                launchBatteryIntent(BatteryOptimizationHelper.openBatteryOptimizationSettingsIntent())
            }
        )
    }
}
