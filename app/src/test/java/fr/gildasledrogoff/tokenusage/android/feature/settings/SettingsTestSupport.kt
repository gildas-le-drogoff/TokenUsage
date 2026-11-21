package fr.gildasledrogoff.tokenusage.feature.settings

import fr.gildasledrogoff.tokenusage.core.domain.model.LockScreenVisibility
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.security.InMemorySharedPreferences
import fr.gildasledrogoff.tokenusage.core.security.SecureStorageFactory
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** In-memory [DisplayPreferencesRepository] that records what the ViewModels write. */
class FakeDisplayPreferences : DisplayPreferencesRepository {
    override val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    override val quotaDisplayMode = MutableStateFlow(QuotaDisplayMode.REMAINING)
    override val lockScreenVisibility = MutableStateFlow(LockScreenVisibility.SYSTEM)
    override var batteryPromptAnswered: Boolean = false

    override fun setThemeMode(mode: ThemeMode) {
        themeMode.value = mode
    }

    override fun setQuotaDisplayMode(mode: QuotaDisplayMode) {
        quotaDisplayMode.value = mode
    }

    override fun setLockScreenVisibility(visibility: LockScreenVisibility) {
        lockScreenVisibility.value = visibility
    }

    override fun markBatteryPromptAnswered() {
        batteryPromptAnswered = true
    }
}

/** A real [EncryptedPrefsManager] backed by an in-memory store. */
fun inMemoryPrefsManager(store: InMemorySharedPreferences = InMemorySharedPreferences()): EncryptedPrefsManager {
    val factory = mock(SecureStorageFactory::class.java)
    `when`(factory.credentials).thenReturn(store)
    return EncryptedPrefsManager(factory)
}

