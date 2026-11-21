package fr.gildasledrogoff.tokenusage.feature.settings.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.gildasledrogoff.tokenusage.core.alert.AlertConfig
import fr.gildasledrogoff.tokenusage.core.alert.AlertPreferencesStore
import fr.gildasledrogoff.tokenusage.core.alert.AlertRule
import fr.gildasledrogoff.tokenusage.core.alert.accountKeyOf
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchRepository
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlertsSettingsViewModel @Inject constructor(
    private val store: AlertPreferencesStore,
    private val prefsManager: EncryptedPrefsManager,
    private val lastFetch: LastFetchRepository
) : ViewModel() {

    private data class Known(
        val accounts: Map<AiService, List<Pair<String, String?>>> = emptyMap(),
        val windowLabels: Map<AiService, List<String>> = emptyMap(),
        val notificationsEnabled: Boolean = true
    )

    private var known = Known()
    private val _uiState = MutableStateFlow(AlertsSettingsUiState())
    val uiState: StateFlow<AlertsSettingsUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    /** Re-reads the configured accounts (they may have changed in the accounts screen). */
    fun reload() {
        known = known.copy(
            accounts = AiService.entries.associateWith { service ->
                prefsManager.getAccounts(service).map { it.id to it.label.takeIf(String::isNotBlank) }
            },
            notificationsEnabled = prefsManager.isNotificationsEnabled()
        )
        publish()
        viewModelScope.launch {
            val labels = try {
                lastFetch.snapshot()?.results.orEmpty()
                    .mapNotNull { result -> result.quota }
                    .groupBy({ it.service }, { quota -> quota.windows.map { it.label } })
                    .mapValues { (_, lists) -> lists.flatten().distinct() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
            known = known.copy(windowLabels = labels)
            publish()
        }
    }

    fun updateServiceRule(service: AiService, rule: AlertRule) {
        store.setServiceRule(service, rule)
        publish()
    }

    fun updateAccountRule(accountKey: String, rule: AlertRule) {
        store.setAccountRule(accountKey, rule)
        publish()
    }

    /** Gives an account its own rule (a copy of the service default), or takes it back to the default. */
    fun setAccountCustomized(service: AiService, accountKey: String, customized: Boolean) {
        val config = store.currentConfig()
        store.setAccountRule(
            accountKey,
            if (customized) config.serviceDefaults[service.name] ?: AlertRule() else null
        )
        publish()
    }

    private fun publish() {
        val config: AlertConfig = store.currentConfig()
        val services = AiService.entries.mapNotNull { service ->
            val accounts = known.accounts[service].orEmpty()
            if (accounts.isEmpty()) return@mapNotNull null
            val default = config.serviceDefaults[service.name] ?: AlertRule()
            AlertServiceUi(
                service = service,
                defaultRule = default,
                accounts = accounts.mapIndexed { index, (id, label) ->
                    val key = accountKeyOf(service, id)
                    AlertAccountUi(
                        key = key,
                        label = label,
                        number = index + 1,
                        rule = config.accountRules[key] ?: default,
                        customized = key in config.accountRules
                    )
                },
                windowLabels = known.windowLabels[service].orEmpty()
            )
        }
        _uiState.value = AlertsSettingsUiState(services, known.notificationsEnabled)
    }
}
