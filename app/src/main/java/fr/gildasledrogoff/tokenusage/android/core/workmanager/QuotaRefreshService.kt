package fr.gildasledrogoff.tokenusage.core.workmanager

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.gildasledrogoff.tokenusage.core.alert.QuotaEventDispatcher
import fr.gildasledrogoff.tokenusage.core.diagnostics.DiagnosticsStore
import fr.gildasledrogoff.tokenusage.core.diagnostics.RefreshOrigin
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.isConnectivityFailure
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.network.NetworkMonitor
import fr.gildasledrogoff.tokenusage.core.notification.QuotaNotificationService
import fr.gildasledrogoff.tokenusage.core.provider.ProviderRegistry
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.tile.QuotaTileService
import fr.gildasledrogoff.tokenusage.core.widget.WidgetAccountQuota
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuotaRefreshService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val providerRegistry: ProviderRegistry,
    private val prefsManager: EncryptedPrefsManager,
    private val demoData: DemoDataRepository,
    private val notificationService: QuotaNotificationService,
    private val quotaEvents: QuotaEventDispatcher,
    private val widgetQuotaPublisher: WidgetQuotaPublisher,
    private val diagnosticsStore: DiagnosticsStore,
    private val networkMonitor: NetworkMonitor,
    private val reconnectRefresh: ReconnectRefresh
) {

    private val singleFlight =
        SingleFlight<Outcome>(CoroutineScope(SupervisorJob() + Dispatchers.Default))

    data class Outcome(
        val quotas: List<QuotaInfo>,
        val anyServiceConfigured: Boolean,
        val offline: Boolean = false
    )

    suspend fun refreshNow(origin: RefreshOrigin = RefreshOrigin.MANUAL_SERVICE): Outcome =
        singleFlight.run { performRefresh(origin) }

    private suspend fun performRefresh(origin: RefreshOrigin): Outcome {
        if (demoData.isEnabled()) {
            val demo = demoData.sampleQuotas()
            val samples = demo.mapNotNull { it.result.getOrNull() }
            if (samples.isNotEmpty()) {
                widgetQuotaPublisher.publish(
                    demo.map {
                        WidgetAccountQuota(
                            it.service,
                            it.accountLabel,
                            it.accountNumber,
                            quota = it.result.getOrNull(),
                            error = it.result.errorOrNull(),
                            fallback = it.fallback
                        )
                    }
                )
            }
            return Outcome(samples, anyServiceConfigured = true)
        }
        val fetches = providerRegistry.targets().map { target ->
            AccountFetch(target.service, target.label, target.number) { target.fetchQuota() }
        }
        if (fetches.isEmpty()) return Outcome(emptyList(), anyServiceConfigured = false)
        if (!networkMonitor.isOnline()) {
            runCatching {
                widgetQuotaPublisher.publish(
                    fetches.map {
                        WidgetAccountQuota(it.service, it.accountLabel, it.accountNumber, quota = null)
                    },
                    offline = true
                )
            }
            if (origin != RefreshOrigin.RECONNECT) reconnectRefresh.onOffline()
            return Outcome(emptyList(), anyServiceConfigured = true, offline = true)
        }
        return try {
            val fetched = coroutineScope {
                fetches.map { fetch ->
                    async {
                        val result = withTimeoutOrNull(REPO_TIMEOUT_MS) { fetch.fetch() }
                            ?: Result.Failure(AppError.NetworkError("refresh timeout"))
                        fetch to result
                    }
                }.awaitAll()
            }
            runCatching {
                diagnosticsStore.recordFetches(
                    origin,
                    fetched.map { (fetch, result) ->
                        DiagnosticsStore.FetchRecord(
                            fetch.service,
                            fetch.accountLabel,
                            fetch.accountNumber,
                            result
                        )
                    }
                )
            }
            val results = fetched.map { (fetch, result) ->
                WidgetAccountQuota(
                    fetch.service,
                    fetch.accountLabel,
                    fetch.accountNumber,
                    result.getOrNull(),
                    error = result.errorOrNull()
                )
            }
            val successfulQuotas = results.mapNotNull { it.quota }
            val offline = fetched.all { (_, result) ->
                (result as? Result.Failure)?.error?.isConnectivityFailure == true
            }
            runCatching { widgetQuotaPublisher.publish(results, offline = offline) }
            if (origin != RefreshOrigin.RECONNECT) {
                if (offline) reconnectRefresh.onOffline() else reconnectRefresh.onOnline()
            }
            if (successfulQuotas.isNotEmpty()) {
                if (prefsManager.isNotificationsEnabled()) {
                    val perService = successfulQuotas.distinctBy { it.service }
                    notificationService.showQuotaNotification(perService)
                    checkForResets(perService)
                }
                quotaEvents.dispatch(successfulQuotas, interactive = false)
            }
            TileService.requestListeningState(
                context,
                ComponentName(context, QuotaTileService::class.java)
            )
            Outcome(successfulQuotas, anyServiceConfigured = true, offline = offline)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Outcome(emptyList(), anyServiceConfigured = true)
        }
    }

    private fun checkForResets(quotas: List<QuotaInfo>) {
        val now = Instant.now()
        for (quota in quotas) {
            val previousResetTimes = prefsManager.loadResetTimes(quota.service)
            for (window in quota.windows) {
                val previousResetAt = previousResetTimes[window.label] ?: continue
                if (previousResetAt.isBefore(now) && window.resetsAt != null && window.resetsAt.isAfter(
                        now
                    )
                ) {
                    notificationService.showResetNotification(quota.service, window.label)
                }
            }
            prefsManager.saveResetTimes(
                quota.service,
                quota.windows.map { it.label to it.resetsAt }
            )
        }
    }

    private class AccountFetch(
        val service: AiService,
        val accountLabel: String? = null,
        val accountNumber: Int? = null,
        val fetch: suspend () -> Result<QuotaInfo, AppError>
    )

    companion object {
        const val REPO_TIMEOUT_MS = 7_000L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface QuotaRefreshEntryPoint {
    fun quotaRefreshService(): QuotaRefreshService
}
