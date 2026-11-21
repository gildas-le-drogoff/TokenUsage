package fr.gildasledrogoff.tokenusage.feature.settings.display

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.feature.settings.common.SettingsSubScreen

/** Display sub-screen: theme, quota display mode, lock-screen visibility, currency conversion. */
@Composable
fun DisplaySettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: DisplaySettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsSubScreen(title = stringResource(R.string.display_title), onNavigateBack = onNavigateBack) {
        DisplaySettingsSection(
            themeMode = uiState.themeMode,
            quotaDisplayMode = uiState.quotaDisplayMode,
            onThemeModeChange = { viewModel.setThemeMode(it) },
            onQuotaDisplayModeChange = { viewModel.setQuotaDisplayMode(it) }
        )

        LockScreenVisibilitySection(
            lockScreenVisibility = uiState.lockScreenVisibility,
            onLockScreenVisibilityChange = { viewModel.setLockScreenVisibility(it) }
        )

        CurrencySettingsSection(
            state = uiState.currencyConversion,
            onDisplayCurrencyChange = { viewModel.setDisplayCurrency(it) },
            onSourceChange = { viewModel.setFxRateSource(it) },
            onApiFetchAllowedChange = { viewModel.setFxApiFetchAllowed(it) },
            onManualUsdEurChange = { viewModel.updateManualUsdEurRate(it) },
            onManualUsdCnyChange = { viewModel.updateManualUsdCnyRate(it) },
            onRefreshRates = { viewModel.refreshFxRates() }
        )
    }
}
