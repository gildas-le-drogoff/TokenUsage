package fr.gildasledrogoff.tokenusage.core.alert

import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.notification.AlertNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuotaAlertCoordinator @Inject constructor(
    private val store: AlertPreferencesStore,
    private val evaluator: QuotaAlertEvaluator,
    private val notifier: AlertNotifier
) {
    private val mutex = Mutex()

    suspend fun process(quotas: List<QuotaInfo>, silent: Boolean = false) {
        if (quotas.isEmpty()) return
        try {
            mutex.withLock {
                val evaluation =
                    evaluator.evaluate(quotas, store.currentConfig(), store.loadState())
                store.saveState(evaluation.state)
                notifier.show(evaluation.alerts, silent)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
