package fr.gildasledrogoff.tokenusage.feature.settings.security

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.gildasledrogoff.tokenusage.core.alert.AlertPreferencesStore
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.security.AppLockManager
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.feature.settings.accounts.AccountsSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** App lock, sample data (debug builds) and the "delete everything" danger zone. */
@HiltViewModel
class SecuritySettingsViewModel @Inject constructor(
    private val prefsManager: EncryptedPrefsManager,
    private val appLock: AppLockManager,
    private val demoData: DemoDataRepository,
    private val accountsSync: AccountsSync,
    private val alertPreferences: AlertPreferencesStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SecuritySettingsUiState(
            appLockEnabled = appLock.isEnabled(),
            demoDataAvailable = demoData.isAvailable,
            demoDataEnabled = demoData.isEnabled()
        )
    )
    val uiState: StateFlow<SecuritySettingsUiState> = _uiState.asStateFlow()

    fun setAppLockEnabled(enabled: Boolean) {
        appLock.setEnabled(enabled)
        _uiState.update { it.copy(appLockEnabled = enabled) }
    }

    fun setDemoDataEnabled(enabled: Boolean) {
        demoData.setEnabled(enabled)
        _uiState.update { it.copy(demoDataEnabled = demoData.isEnabled()) }
    }

    fun showDeleteConfirmDialog() {
        _uiState.update { it.copy(showDeleteConfirmDialog = true) }
    }

    fun dismissDeleteConfirmDialog() {
        _uiState.update { it.copy(showDeleteConfirmDialog = false) }
    }

    fun deleteAllCredentials() {
        prefsManager.deleteAllCredentials()
        alertPreferences.clear()
        accountsSync.notifyExternalChange()
        _uiState.update {
            SecuritySettingsUiState(
                appLockEnabled = it.appLockEnabled,
                demoDataAvailable = it.demoDataAvailable,
                demoDataEnabled = it.demoDataEnabled
            )
        }
    }
}
