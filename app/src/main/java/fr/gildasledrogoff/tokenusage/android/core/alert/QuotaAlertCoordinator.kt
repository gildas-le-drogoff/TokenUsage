package fr.gildasledrogoff.tokenusage.core.alert

import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.notification.AlertNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entry point of the refresh code: evaluates the alert rules on the quotas of a
 * refresh, remembers what was raised, and shows the notifications.
 */
@Singleton
class QuotaAlertCoordinator @Inject constructor(
    private val store: AlertPreferencesStore,
    private val evaluator: QuotaAlertEvaluator,
    private val notifier: AlertNotifier
) {
    // Several refreshes (worker, widget, notification action) may overlap.
    private val mutex = Mutex()

    /** Never throws: an alert failure must not break a quota refresh. */
    suspend fun process(quotas: List<QuotaInfo>) {
        if (quotas.isEmpty()) return
        try {
            mutex.withLock {
                val evaluation = evaluator.evaluate(quotas, store.currentConfig(), store.loadState())
                store.saveState(evaluation.state)
                notifier.show(evaluation.alerts)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}
