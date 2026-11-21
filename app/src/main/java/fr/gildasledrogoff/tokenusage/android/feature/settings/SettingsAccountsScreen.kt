package fr.gildasledrogoff.tokenusage.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.feature.settings.accounts.AccountsSettingsContent
import fr.gildasledrogoff.tokenusage.feature.settings.common.SettingsSubScreen

@Composable
fun SettingsAccountsScreen(onNavigateBack: () -> Unit) {
    SettingsSubScreen(
        title = stringResource(R.string.settings_menu_accounts),
        onNavigateBack = onNavigateBack
    ) {
        AccountsSettingsContent()
    }
}
