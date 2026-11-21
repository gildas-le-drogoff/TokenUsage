package fr.gildasledrogoff.tokenusage.core.alert

import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.notification.AlertNotifier
import fr.gildasledrogoff.tokenusage.core.security.SecureStorageFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuotaLifecycleTracker @Inject constructor(
    private val storage: SecureStorageFactory,
    private val alertStore: AlertPreferencesStore,
    private val evaluator: QuotaLifecycleEvaluator,
    private val notifier: AlertNotifier,
    private val celebrations: ResetCelebrationBus,
    private val clock: Clock
) {
    private val mutex = Mutex()

    /**
     * @param notify post the resulting notifications (general notification switch); the memory is
     *   updated either way so that turning notifications back on does not replay stale transitions.
     * @param silent post them without sound nor heads-up (refresh started from the dashboard).
     */
    suspend fun process(quotas: List<QuotaInfo>, notify: Boolean = true, silent: Boolean = false) {
        if (quotas.isEmpty()) return
        try {
            mutex.withLock {
                val prefs = storage.credentials
                val state = prefs.getString(KEY_STATE, null)?.let {
                    runCatching { json.decodeFromString<LifecycleState>(it) }.getOrNull()
                } ?: LifecycleState()
                val result = evaluator.evaluate(quotas, alertStore.currentConfig(), state, clock.instant())
                prefs.edit().putString(KEY_STATE, json.encodeToString(result.state)).apply()
                if (notify) notifier.show(result.alerts, silent)
                result.weeklyResets.firstOrNull()?.let { celebrations.trigger(it.quota.service) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val KEY_STATE = "quota_lifecycle_state_v1"
        val json = Json { ignoreUnknownKeys = true }
    }
}
