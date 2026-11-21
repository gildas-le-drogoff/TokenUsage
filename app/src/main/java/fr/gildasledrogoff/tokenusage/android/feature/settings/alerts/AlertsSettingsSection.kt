package fr.gildasledrogoff.tokenusage.feature.settings.alerts

import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.alert.AlertRule
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.feature.settings.common.SettingsSubScreen

/** Alerts sub-screen of the settings. */
@Composable
fun AlertsSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: AlertsSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.reload()
        onPauseOrDispose { }
    }
    SettingsSubScreen(title = stringResource(R.string.alerts_title), onNavigateBack = onNavigateBack) {
        AlertsSettingsSection(
            state = state,
            onServiceRuleChange = viewModel::updateServiceRule,
            onAccountRuleChange = viewModel::updateAccountRule,
            onAccountCustomizedChange = viewModel::setAccountCustomized
        )
    }
}

@Composable
internal fun AlertsSettingsSection(
    state: AlertsSettingsUiState,
    onServiceRuleChange: (AiService, AlertRule) -> Unit,
    onAccountRuleChange: (String, AlertRule) -> Unit,
    onAccountCustomizedChange: (AiService, String, Boolean) -> Unit
) {
    Text(
        text = stringResource(R.string.alerts_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (!state.notificationsEnabled) {
        Text(
            text = stringResource(R.string.alerts_notifications_off),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
    if (state.services.isEmpty()) {
        Text(
            text = stringResource(R.string.alerts_no_accounts),
            style = MaterialTheme.typography.bodyMedium
        )
    }
    state.services.forEach { entry ->
        AlertServiceCard(
            entry = entry,
            onServiceRuleChange = { onServiceRuleChange(entry.service, it) },
            onAccountRuleChange = onAccountRuleChange,
            onAccountCustomizedChange = { key, customized -> onAccountCustomizedChange(entry.service, key, customized) }
        )
    }
}

@Composable
private fun AlertServiceCard(
    entry: AlertServiceUi,
    onServiceRuleChange: (AlertRule) -> Unit,
    onAccountRuleChange: (String, AlertRule) -> Unit,
    onAccountCustomizedChange: (String, Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = entry.service.displayName, style = MaterialTheme.typography.titleMedium)
            if (entry.hasSeveralAccounts) {
                Text(
                    text = stringResource(R.string.alerts_default_rule),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            AlertRuleEditor(
                rule = entry.defaultRule,
                supportsBalance = entry.supportsBalance,
                windowLabels = entry.windowLabels,
                onRuleChange = onServiceRuleChange
            )
            if (entry.hasSeveralAccounts) {
                entry.accounts.forEach { account ->
                    HorizontalDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = account.label ?: stringResource(R.string.alerts_account_n, account.number),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(
                                    if (account.customized) R.string.alerts_account_custom else R.string.alerts_account_uses_default
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = account.customized,
                            onCheckedChange = { onAccountCustomizedChange(account.key, it) }
                        )
                    }
                    if (account.customized) {
                        AlertRuleEditor(
                            rule = account.rule,
                            supportsBalance = entry.supportsBalance,
                            windowLabels = entry.windowLabels,
                            onRuleChange = { onAccountRuleChange(account.key, it) }
                        )
                    }
                }
            }
        }
    }
}
