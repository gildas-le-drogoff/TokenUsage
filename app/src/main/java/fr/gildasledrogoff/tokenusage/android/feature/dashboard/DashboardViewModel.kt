package fr.gildasledrogoff.tokenusage.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.gildasledrogoff.tokenusage.core.data.FetchRateLimiter
import fr.gildasledrogoff.tokenusage.core.data.WINDOW_7D
import fr.gildasledrogoff.tokenusage.core.data.claudeUtilizationKey
import fr.gildasledrogoff.tokenusage.core.domain.model.HistoricalUsagePace
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.BalancePace
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.core.domain.model.isConnectivityFailure
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.CachedQuotaResult
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchSnapshot
import fr.gildasledrogoff.tokenusage.core.domain.repository.keepingLastKnownQuotas
import fr.gildasledrogoff.tokenusage.core.network.NetworkMonitor
import fr.gildasledrogoff.tokenusage.core.provider.ProviderRegistry
import fr.gildasledrogoff.tokenusage.core.alert.BalanceSeriesSource
import fr.gildasledrogoff.tokenusage.core.alert.QuotaEventDispatcher
import fr.gildasledrogoff.tokenusage.core.alert.ResetCelebration
import fr.gildasledrogoff.tokenusage.core.alert.ResetCelebrationBus
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.status.ProviderStatus
import fr.gildasledrogoff.tokenusage.core.status.ProviderStatusRepository
import fr.gildasledrogoff.tokenusage.core.widget.WidgetAccountQuota
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import fr.gildasledrogoff.tokenusage.core.workmanager.ReconnectRefresh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

private data class FetchOutcome(
    val service: AiService,
    val accountLabel: String?,
    val result: Result<QuotaInfo, AppError>,
    val accountNumber: Int? = null,
    val accountId: String? = null,
    val fallback: QuotaInfo? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val prefsManager: EncryptedPrefsManager,
    private val demoData: DemoDataRepository,
    private val exchangeRates: ExchangeRateRepository,
    private val widgetQuotaPublisher: WidgetQuotaPublisher,
    private val balanceHistory: BalanceHistoryRepository,
    private val displayPreferences: DisplayPreferencesRepository,
    private val lastFetchRepository: LastFetchRepository,
    private val rateLimiter: FetchRateLimiter,
    private val clock: Clock,
    private val statusRepository: ProviderStatusRepository,
    private val celebrationBus: ResetCelebrationBus,
    private val balanceSeries: BalanceSeriesSource,
    private val quotaEvents: QuotaEventDispatcher,
    private val networkMonitor: NetworkMonitor,
    private val reconnectRefresh: ReconnectRefresh
) : ViewModel() {
    private val demoStatuses by lazy { MutableStateFlow(demoData.sampleProviderStatuses()).asStateFlow() }
    val providerStatuses: StateFlow<Map<AiService, ProviderStatus>>
        get() = if (demoData.isEnabled()) demoStatuses else statusRepository.statuses
    val celebration: StateFlow<ResetCelebration?>
        get() = celebrationBus.pending
    fun consumeCelebration() = celebrationBus.consume()

    fun toggleTheme(isDarkNow: Boolean) {
        displayPreferences.setThemeMode(if (isDarkNow) ThemeMode.LIGHT else ThemeMode.DARK)
        viewModelScope.launch { widgetQuotaPublisher.refreshWidgets() }
    }

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()
    private val _consumptionChart =
        MutableStateFlow<ConsumptionChartUiState>(ConsumptionChartUiState.Idle)
    val consumptionChart: StateFlow<ConsumptionChartUiState> = _consumptionChart.asStateFlow()
    private var consumptionJob: Job? = null
    fun loadConsumption() {
        consumptionJob?.cancel()
        consumptionJob = viewModelScope.launch {
            val previous = _consumptionChart.value as? ConsumptionChartUiState.Ready
            if (previous == null) _consumptionChart.value = ConsumptionChartUiState.Loading
            val demo = demoData.isEnabled()
            val history = if (demo) demoData.history else balanceHistory
            val tracked: List<Triple<AiService, String, String>> = if (demo) {
                demoData.sampleDeepseekAccounts()
                    .map { Triple(AiService.DEEPSEEK, it.id, it.label) }
            } else {
                providerRegistry.providers.filter { it.tracksBalanceHistory }.flatMap { provider ->
                    provider.accounts().map { Triple(provider.service, it.id, it.label) }
                }
            }
            val charts = tracked.map { (service, accountId, label) ->
                val points = history.consumption(service, accountId, ConsumptionGranularity.DAILY)
                val currency = points.lastOrNull()?.currency ?: ""
                val rate = exchangeRates.displayRate(currency)
                AccountConsumptionUi(
                    accountId = accountId,
                    label = label,
                    points = if (rate == null) points else points.map { point ->
                        if (point.currency == currency) {
                            point.copy(consumed = rate.convert(point.consumed), currency = rate.to)
                        } else point
                    },
                    currency = rate?.to ?: currency
                )
            }
            _consumptionChart.value = if (charts.isEmpty()) {
                ConsumptionChartUiState.Idle
            } else {
                val keepSelection =
                    previous?.selectedAccountId?.takeIf { id -> charts.any { it.accountId == id } }
                ConsumptionChartUiState.Ready(
                    accounts = charts,
                    selectedAccountId = keepSelection
                        ?: (charts.firstOrNull { it.points.isNotEmpty() }
                            ?: charts.first()).accountId
                )
            }
        }
    }

    fun selectConsumptionAccount(accountId: String) {
        _consumptionChart.update { state ->
            if (state is ConsumptionChartUiState.Ready) state.copy(selectedAccountId = accountId) else state
        }
    }

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private var lastCredentialsFingerprint: String? = null

    init {
        viewModelScope.launch {
            displayPreferences.insightVisibility.collect { visibility ->
                _uiState.update { it.withInsightVisibility(visibility) }
            }
        }
        refresh()
    }

    suspend fun observeConnectivity() {
        networkMonitor.onlineChanges().collectLatest { online ->
            if (!online || !isShowingOfflineData()) return@collectLatest
            delay(RECONNECT_SETTLE_MS)
            if (isShowingOfflineData() && !_isRefreshing.value && networkMonitor.isOnline()) {
                doRefresh(resetState = false, bypassCooldown = true)
            }
        }
    }

    private fun isShowingOfflineData(): Boolean = when (val state = _uiState.value) {
        is DashboardUiState.Offline -> true
        is DashboardUiState.Error -> state.offline
        else -> false
    }

    private fun publish(state: DashboardUiState) {
        _uiState.value = state.withInsightVisibility(displayPreferences.insightVisibility.value)
    }

    fun refresh() {
        doRefresh(resetState = true)
    }

    fun refreshIfCredentialsChanged() {
        if (credentialsFingerprint() != lastCredentialsFingerprint) {
            doRefresh(resetState = false, bypassCooldown = true)
        }
    }

    private fun credentialsFingerprint(): String = listOf(
        demoData.isEnabled(),
        providerRegistry.providers.joinToString(";") { provider ->
            provider.accounts().joinToString(",") {
                "${it.id}:${it.label}:${it.credential.accessToken.hashCode()}:${it.credential.refreshToken.hashCode()}"
            } + provider.form.options.joinToString("") { option ->
                provider.optionValue(option.key).toString()
            }
        },
        prefsManager.getDisplayCurrency(),
        prefsManager.getFxRateSource(),
        prefsManager.getManualUsdEurRate(),
        prefsManager.getManualUsdCnyRate()
    ).joinToString("|")

    private fun doRefresh(resetState: Boolean, bypassCooldown: Boolean = false) {
        lastCredentialsFingerprint = credentialsFingerprint()
        if (!demoData.isEnabled()) {
            viewModelScope.launch { runCatching { statusRepository.refresh(force = resetState) } }
        }
        viewModelScope.launch {
            if (demoData.isEnabled()) {
                enterRefreshing(resetState)
                val results = demoData.sampleQuotas().map {
                    FetchOutcome(
                        it.service,
                        it.accountLabel,
                        it.result,
                        it.accountNumber,
                        it.accountId,
                        fallback = it.fallback
                    )
                }
                applyResults(results, clock.instant())
                if (_consumptionChart.value is ConsumptionChartUiState.Ready) loadConsumption()
                _isRefreshing.value = false
                return@launch
            }
            val cached = lastFetchRepository.snapshot()
            if (!bypassCooldown && rateLimiter.isCoolingDown(cached?.fetchedAt)) {
                serveFromCache(cached)
                return@launch
            }
            if (!networkMonitor.isOnline()) {
                serveOffline(cached)
                reconnectRefresh.onOffline()
                return@launch
            }
            enterRefreshing(resetState)
            val fetched = fetchAllServices()
            if (fetched.isEmpty()) {
                publish(DashboardUiState.Success(emptyList(), clock.instant()))
                _isRefreshing.value = false
                return@launch
            }
            val fetchedAt = clock.instant()
            val toCache = fetched.map { it.toCachedResult() }.keepingLastKnownQuotas(cached?.results)
            runCatching { lastFetchRepository.record(fetchedAt, toCache) }
            val results = fetched.zip(toCache) { outcome, entry ->
                if (outcome.result is Result.Failure) outcome.copy(fallback = entry.quota) else outcome
            }
            val offline = results.all { outcome ->
                (outcome.result as? Result.Failure)?.error?.isConnectivityFailure == true
            }
            applyResults(results, fetchedAt, offline = offline)
            if (offline) reconnectRefresh.onOffline() else reconnectRefresh.onOnline()
            if (_consumptionChart.value is ConsumptionChartUiState.Ready) loadConsumption()
            _isRefreshing.value = false
            quotaEvents.dispatch(fetched.mapNotNull { it.result.getOrNull() }, interactive = true)
        }
    }

    private fun enterRefreshing(resetState: Boolean) {
        _isRefreshing.value = true
        val currentState = _uiState.value
        if (resetState || currentState is DashboardUiState.Error || currentState is DashboardUiState.Loading) {
            publish(DashboardUiState.Loading)
        }
    }

    private suspend fun fetchAllServices(): List<FetchOutcome> = coroutineScope {
        providerRegistry.targets().map { target ->
            async {
                FetchOutcome(
                    service = target.service,
                    accountLabel = target.label,
                    result = target.fetchQuota(),
                    accountNumber = target.number,
                    accountId = target.account.id
                )
            }
        }.awaitAll()
    }

    private suspend fun serveFromCache(snapshot: LastFetchSnapshot?) {
        val results = snapshot?.results?.map { it.toFetchOutcome() }
        if (results == null) {
            val current = _uiState.value
            if (current is DashboardUiState.Loading || current is DashboardUiState.Error) {
                publish(DashboardUiState.Success(emptyList(), snapshot?.fetchedAt ?: clock.instant()))
            }
            return
        }
        applyResults(results, snapshot.fetchedAt, publishWidgets = false)
    }

    private suspend fun serveOffline(snapshot: LastFetchSnapshot?) {
        val lastKnown = snapshot?.results.orEmpty()
            .filter { it.quota != null && it.accountId != null }
            .associateBy { it.service to it.accountId }
        val results = providerRegistry.targets().map { target ->
            FetchOutcome(
                service = target.service,
                accountLabel = target.label,
                result = Result.Failure(AppError.NetworkError("")),
                accountNumber = target.number,
                accountId = target.account.id,
                fallback = lastKnown[target.service to target.account.id]?.quota
            )
        }
        if (results.isEmpty()) {
            publish(DashboardUiState.Success(emptyList(), clock.instant()))
            return
        }
        applyResults(results, snapshot?.fetchedAt ?: clock.instant(), offline = true)
    }

    private suspend fun applyResults(
        results: List<FetchOutcome>,
        lastUpdatedAt: Instant,
        publishWidgets: Boolean = true,
        offline: Boolean = false
    ) {
        if (publishWidgets) {
            val entries = results.map {
                WidgetAccountQuota(
                    it.service,
                    it.accountLabel,
                    it.accountNumber,
                    it.result.getOrNull(),
                    error = it.result.errorOrNull(),
                    fallback = it.fallback
                )
            }
            viewModelScope.launch {
                runCatching { widgetQuotaPublisher.publish(entries, offline = offline) }
            }
        }
        val fxRates = resolveDisplayRates(results)
        val successCards = mutableListOf<ServiceCardData>()
        val errors = mutableMapOf<AiService, AppError>()
        for (outcome in results) {
            when (val result = outcome.result) {
                is Result.Success -> {
                    successCards.add(
                        mapToCardData(result.value, outcome.accountLabel, fxRates)
                            .copy(
                                accountNumber = outcome.accountNumber,
                                accountId = outcome.accountId
                            )
                            .withBalancePace()
                            .withHistoricalPace()
                    )
                }

                is Result.Failure -> {
                    errors[outcome.service] = result.error
                    val fallback = outcome.fallback
                    if (fallback != null) {
                        successCards.add(
                            mapToCardData(fallback, outcome.accountLabel, fxRates)
                                .copy(
                                    accountNumber = outcome.accountNumber,
                                    accountId = outcome.accountId,
                                    error = result.error,
                                    staleSince = fallback.fetchedAt
                                )
                                .withBalancePace()
                                .withHistoricalPace()
                        )
                        continue
                    }
                    successCards.add(
                        ServiceCardData(
                            service = outcome.service,
                            windows = emptyList(),
                            extraUsage = null,
                            tier = null,
                            accountLabel = outcome.accountLabel,
                            accountNumber = outcome.accountNumber,
                            accountId = outcome.accountId,
                            error = result.error
                        )
                    )
                }
            }
        }
        val sortedCards = successCards.sortedByDescending { card ->
            card.windows.maxOfOrNull { it.utilization } ?: 0.0
        }
        publish(
            when {
                errors.isEmpty() -> DashboardUiState.Success(sortedCards, lastUpdatedAt)
                successCards.none { it.hasData } ->
                    DashboardUiState.Error(errors.values.first(), offline = offline)

                offline -> DashboardUiState.Offline(
                    sortedCards,
                    successCards.mapNotNull { it.staleSince }.maxOrNull() ?: lastUpdatedAt
                )

                else -> DashboardUiState.PartialSuccess(sortedCards, errors, lastUpdatedAt)
            }
        )
    }

    private fun FetchOutcome.toCachedResult() = CachedQuotaResult(
        service = service,
        accountLabel = accountLabel,
        accountNumber = accountNumber,
        accountId = accountId,
        quota = result.getOrNull(),
        error = result.errorOrNull()
    )

    private fun CachedQuotaResult.toFetchOutcome(): FetchOutcome {
        val failure = error
        return FetchOutcome(
            service = service,
            accountLabel = accountLabel,
            result = when {
                failure != null -> Result.Failure(failure)
                quota != null -> Result.Success(quota)
                else -> Result.Failure(AppError.NetworkError("No cached payload for this account"))
            },
            accountNumber = accountNumber,
            accountId = accountId ?: quota?.accountId,
            fallback = quota.takeIf { failure != null }
        )
    }

    private suspend fun resolveDisplayRates(results: List<FetchOutcome>): Map<String, FxRate> {
        val currencies = results
            .mapNotNull { (it.result as? Result.Success)?.value }
            .flatMap { quota ->
                quota.accountBalances.mapNotNull { it.balance?.currency } +
                        quota.spending.map { it.currency } +
                        listOfNotNull(quota.extraUsage?.currency)
            }
            .toSet()
        return currencies
            .mapNotNull { currency -> exchangeRates.displayRate(currency)?.let { currency to it } }
            .toMap()
    }

    private suspend fun ServiceCardData.withBalancePace(): ServiceCardData {
        val id = accountId
        if (service !in PACED_BALANCE_SERVICES || id == null || accountBalances.size != 1) return this
        val now = clock.instant()
        val since = now.minus(BalancePace.LOOKBACK)
        val pace = try {
            val samples = if (demoData.isEnabled()) {
                demoData.sampleBalanceSeries(service, id, since)
            } else {
                balanceSeries.series(service, id, since)
            }
            BalancePace.compute(samples, now)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return this
        return copy(accountBalances = accountBalances.map { it.copy(pace = pace) })
    }

    private suspend fun ServiceCardData.withHistoricalPace(): ServiceCardData {
        val id = accountId
        if (service != AiService.CLAUDE || id == null) return this
        val now = clock.instant()
        return copy(windows = windows.map { window ->
            val resetsAt = window.resetsAt
            if (window.label != WINDOW_7D || resetsAt == null) return@map window
            val minutes = window.windowMinutes ?: WEEKLY_WINDOW_MINUTES
            val since = HistoricalUsagePace.lookbackStart(resetsAt, minutes)
            val pace = try {
                val samples = if (demoData.isEnabled()) {
                    demoData.sampleUtilizationSeries(id, WINDOW_7D, since, now)
                } else {
                    balanceSeries.series(AiService.CLAUDE, claudeUtilizationKey(id, WINDOW_7D), since)
                }
                HistoricalUsagePace.compute(window.utilization, resetsAt, minutes, samples, now)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            window.copy(historicalPace = pace)
        })
    }

    private fun mapToCardData(
        quotaInfo: QuotaInfo,
        accountLabel: String? = null,
        fxRates: Map<String, FxRate> = emptyMap()
    ): ServiceCardData {
        return ServiceCardData(
            service = quotaInfo.service,
            hasConsumptionHistory = providerRegistry.provider(quotaInfo.service).tracksBalanceHistory,
            accountLabel = accountLabel,
            windows = quotaInfo.windows.map { window ->
                UsageWindowUi(
                    label = window.label,
                    utilization = window.utilization,
                    resetsAt = window.resetsAt,
                    severity = window.severity,
                    windowMinutes = window.windowMinutes
                )
            },
            weeklyBreakdown = quotaInfo.weeklyBreakdown.map { row ->
                UsageBreakdownRowUi(label = row.label, percent = row.percent)
            },
            extraUsage = quotaInfo.extraUsage?.let { extra ->
                ExtraUsageUi(
                    monthlyLimit = extra.monthlyLimit,
                    usedCredits = extra.usedCredits,
                    utilization = extra.utilization,
                    currency = extra.currency,
                    rate = fxRates[extra.currency]
                )
            },
            accountBalances = quotaInfo.accountBalances.map { account ->
                AccountBalanceUi(
                    label = account.label,
                    currency = account.balance?.currency ?: "",
                    totalBalance = account.balance?.totalBalance ?: 0.0,
                    grantedBalance = account.balance?.grantedBalance,
                    toppedUpBalance = account.balance?.toppedUpBalance,
                    isAvailable = account.balance?.isAvailable ?: false,
                    rate = account.balance?.currency?.let { fxRates[it] },
                    error = account.error
                )
            },
            spending = quotaInfo.spending.map { spend ->
                SpendRowUi(
                    period = spend.period,
                    amount = spend.amount,
                    currency = spend.currency,
                    rate = fxRates[spend.currency]
                )
            },
            tier = quotaInfo.tier
        )
    }

    private companion object {
        val PACED_BALANCE_SERVICES = setOf(AiService.DEEPSEEK, AiService.CLAUDE_PLATFORM)
        const val WEEKLY_WINDOW_MINUTES = 7 * 24 * 60
        const val RECONNECT_SETTLE_MS = 1_500L
    }
}
