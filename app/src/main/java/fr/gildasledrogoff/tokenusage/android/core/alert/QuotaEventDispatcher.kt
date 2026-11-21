package fr.gildasledrogoff.tokenusage.core.alert

import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuotaEventDispatcher @Inject constructor(
    private val prefsManager: EncryptedPrefsManager,
    private val alertCoordinator: QuotaAlertCoordinator,
    private val lifecycleTracker: QuotaLifecycleTracker
) {
    
    suspend fun dispatch(quotas: List<QuotaInfo>, interactive: Boolean) {
        if (quotas.isEmpty()) return
        val notify = prefsManager.isNotificationsEnabled()
        if (notify) alertCoordinator.process(quotas, silent = interactive)
        lifecycleTracker.process(quotas, notify = notify, silent = interactive)
    }
}
