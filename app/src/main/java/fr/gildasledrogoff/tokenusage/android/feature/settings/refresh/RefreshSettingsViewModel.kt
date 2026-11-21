package fr.gildasledrogoff.tokenusage.feature.settings.refresh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.status.ProviderStatusRepository
import fr.gildasledrogoff.tokenusage.core.status.StatusCheckSettings
import fr.gildasledrogoff.tokenusage.core.workmanager.ReconnectRefresh
import fr.gildasledrogoff.tokenusage.core.workmanager.RefreshScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RefreshSettingsViewModel @Inject constructor(
    private val prefsManager: EncryptedPrefsManager,
    private val refreshScheduler: RefreshScheduler,
    private val displayPreferences: DisplayPreferencesRepository,
    private val statusRepository: ProviderStatusRepository,
    private val reconnectRefresh: ReconnectRefresh
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        RefreshSettingsUiState(
            refreshIntervalMinutes = prefsManager.getRefreshInterval(),
            notificationsEnabled = prefsManager.isNotificationsEnabled(),
            reconnectRefreshEnabled = reconnectRefresh.isEnabled(),
            statusCheck = statusRepository.settings.value
        )
    )
    val uiState: StateFlow<RefreshSettingsUiState> = _uiState.asStateFlow()
    fun onBatteryChoiceMade() {
        displayPreferences.markBatteryPromptAnswered()
    }

    fun setRefreshInterval(minutes: Long) {
        prefsManager.setRefreshInterval(minutes)
        refreshScheduler.apply(minutes)
        _uiState.update { it.copy(refreshIntervalMinutes = minutes) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefsManager.setNotificationsEnabled(enabled)
        _uiState.update { it.copy(notificationsEnabled = enabled) }
    }

    fun setReconnectRefreshEnabled(enabled: Boolean) {
        reconnectRefresh.setEnabled(enabled)
        _uiState.update { it.copy(reconnectRefreshEnabled = enabled) }
    }

    fun setStatusCheckForAll(enabled: Boolean) =
        applyStatusCheck(_uiState.value.statusCheck.copy(allProviders = enabled))

    fun setStatusCheckForProvider(service: AiService, enabled: Boolean) =
        applyStatusCheck(_uiState.value.statusCheck.withProvider(service, enabled))

    private fun applyStatusCheck(settings: StatusCheckSettings) {
        val previous = _uiState.value.statusCheck
        statusRepository.updateSettings(settings)
        _uiState.update { it.copy(statusCheck = settings) }
        if (StatusCheckSettings.supportedServices.any { settings.isEnabled(it) && !previous.isEnabled(it) }) {
            viewModelScope.launch { runCatching { statusRepository.refresh() } }
        }
    }
}
