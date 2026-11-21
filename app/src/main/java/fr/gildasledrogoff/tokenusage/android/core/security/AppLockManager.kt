package fr.gildasledrogoff.tokenusage.core.security
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class AppLockManager @Inject constructor(
    private val prefsManager: EncryptedPrefsManager
) {
    private val _isLocked = MutableStateFlow(prefsManager.isAppLockEnabled())
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()
    fun isEnabled(): Boolean = prefsManager.isAppLockEnabled()
    fun onAppHidden() {
        if (prefsManager.isAppLockEnabled()) _isLocked.value = true
    }
    fun unlock() {
        _isLocked.value = false
    }
    fun setEnabled(enabled: Boolean) {
        prefsManager.setAppLockEnabled(enabled)
        if (!enabled) _isLocked.value = false
    }
}
