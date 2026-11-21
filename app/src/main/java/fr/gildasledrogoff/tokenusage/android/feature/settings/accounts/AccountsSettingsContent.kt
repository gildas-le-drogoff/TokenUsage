package fr.gildasledrogoff.tokenusage.feature.settings.accounts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AccountsSettingsContent(
    modifier: Modifier = Modifier,
    viewModel: AccountsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        viewModel.providers.forEach { provider ->
            val service = provider.service
            ProviderAccountsSection(
                provider = provider,
                accounts = uiState.accounts[service].orEmpty(),
                optionValue = { key -> uiState.option(service, key) },
                onAddAccount = { viewModel.addAccount(service) },
                onRemoveAccount = { viewModel.removeAccount(service, it) },
                onFieldChange = { accountId, key, value -> viewModel.updateField(service, accountId, key, value) },
                onValidate = { viewModel.validate(service, it) },
                onOptionChange = { key, value -> viewModel.setOption(service, key, value) },
                onSignIn = { viewModel.startDeviceLogin(service, it) }
            )
        }
    }
    uiState.deviceLogin?.let { login ->
        CopilotLoginDialog(
            state = login,
            onRetry = { viewModel.retryDeviceLogin() },
            onDismiss = { viewModel.cancelDeviceLogin() }
        )
    }
}
