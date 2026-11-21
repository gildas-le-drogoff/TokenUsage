package fr.gildasledrogoff.tokenusage.feature.settings.refresh

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.workmanager.RefreshScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** Refresh interval, notifications and background-refresh (battery) prompt. */
@HiltViewModel
class RefreshSettingsViewModel @Inject constructor(
    private val prefsManager: EncryptedPrefsManager,
    private val refreshScheduler: RefreshScheduler,
    private val displayPreferences: DisplayPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        RefreshSettingsUiState(
            refreshIntervalMinutes = prefsManager.getRefreshInterval(),
            notificationsEnabled = prefsManager.isNotificationsEnabled()
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
}
