package fr.gildasledrogoff.tokenusage.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.core.domain.model.accountNaming
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.CachedQuotaResult
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchSnapshot
import fr.gildasledrogoff.tokenusage.core.data.FetchRateLimiter
import fr.gildasledrogoff.tokenusage.core.provider.ProviderRegistry
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.widget.WidgetAccountQuota
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val accountId: String? = null
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
    private val clock: Clock
) : ViewModel() {


    fun toggleTheme(isDarkNow: Boolean) {
        displayPreferences.setThemeMode(if (isDarkNow) ThemeMode.LIGHT else ThemeMode.DARK)
        viewModelScope.launch { widgetQuotaPublisher.refreshWidgets() }
    }

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _consumptionChart = MutableStateFlow<ConsumptionChartUiState>(ConsumptionChartUiState.Idle)
    val consumptionChart: StateFlow<ConsumptionChartUiState> = _consumptionChart.asStateFlow()

    private var consumptionJob: Job? = null


    fun loadConsumption() {
        consumptionJob?.cancel()
        consumptionJob = viewModelScope.launch {
            val previous = _consumptionChart.value as? ConsumptionChartUiState.Ready
            if (previous == null) _consumptionChart.value = ConsumptionChartUiState.Loading


            val demo = demoData.isEnabled()
            val history = if (demo) demoData.history else balanceHistory
            // (service, account id, label) of every account whose balance history is recorded.
            val tracked: List<Triple<AiService, String, String>> = if (demo) {
                demoData.sampleDeepseekAccounts().map { Triple(AiService.DEEPSEEK, it.id, it.label) }
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
                val keepSelection = previous?.selectedAccountId?.takeIf { id -> charts.any { it.accountId == id } }
                ConsumptionChartUiState.Ready(
                    accounts = charts,
                    selectedAccountId = keepSelection
                        ?: (charts.firstOrNull { it.points.isNotEmpty() } ?: charts.first()).accountId
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
        refresh()
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
            } + provider.form.options.joinToString("") { option -> provider.optionValue(option.key).toString() }
        },
        prefsManager.getDisplayCurrency(),
        prefsManager.getFxRateSource(),
        prefsManager.getManualUsdEurRate(),
        prefsManager.getManualUsdCnyRate()
    ).joinToString("|")

    private fun doRefresh(resetState: Boolean, bypassCooldown: Boolean = false) {
        lastCredentialsFingerprint = credentialsFingerprint()

        viewModelScope.launch {
            if (demoData.isEnabled()) {
                enterRefreshing(resetState)
                val results = demoData.sampleQuotas().map {
                    FetchOutcome(it.service, it.accountLabel, it.result, it.accountNumber, it.accountId)
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

            enterRefreshing(resetState)
            val results = fetchAllServices()

            if (results.isEmpty()) {
                _uiState.value = DashboardUiState.Success(emptyList(), clock.instant())
                _isRefreshing.value = false
                return@launch
            }

            val fetchedAt = clock.instant()
            runCatching {
                lastFetchRepository.record(fetchedAt, results.map { it.toCachedResult() })
            }

            applyResults(results, fetchedAt)
            if (_consumptionChart.value is ConsumptionChartUiState.Ready) loadConsumption()

            _isRefreshing.value = false
        }
    }

    private fun enterRefreshing(resetState: Boolean) {
        _isRefreshing.value = true
        val currentState = _uiState.value
        if (resetState || currentState is DashboardUiState.Error || currentState is DashboardUiState.Loading) {
            _uiState.value = DashboardUiState.Loading
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
                _uiState.value = DashboardUiState.Success(emptyList(), snapshot?.fetchedAt ?: clock.instant())
            }
            return
        }
        applyResults(results, snapshot.fetchedAt, publishWidgets = false)
    }

    private suspend fun applyResults(
        results: List<FetchOutcome>,
        lastUpdatedAt: Instant,
        publishWidgets: Boolean = true
    ) {
        if (publishWidgets) {
            val entries = results.map {
                WidgetAccountQuota(it.service, it.accountLabel, it.accountNumber, it.result.getOrNull())
            }
            viewModelScope.launch {
                runCatching { widgetQuotaPublisher.publish(entries) }
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
                            .copy(accountNumber = outcome.accountNumber, accountId = outcome.accountId)
                    )
                }
                is Result.Failure -> {
                    errors[outcome.service] = result.error
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

        _uiState.value = if (errors.isEmpty()) {
            DashboardUiState.Success(sortedCards, lastUpdatedAt)
        } else if (successCards.all { it.error != null }) {
            DashboardUiState.Error(errors.values.first())
        } else {
            DashboardUiState.PartialSuccess(sortedCards, errors, lastUpdatedAt)
        }
    }

    private fun FetchOutcome.toCachedResult() = CachedQuotaResult(
        service = service,
        accountLabel = accountLabel,
        accountNumber = accountNumber,
        accountId = accountId,
        quota = result.getOrNull(),
        error = result.errorOrNull()
    )

    private fun CachedQuotaResult.toFetchOutcome() = FetchOutcome(
        service = service,
        accountLabel = accountLabel,
        result = quota?.let { Result.Success(it) }
            ?: Result.Failure(error ?: AppError.NetworkError("No cached payload for this account")),
        accountNumber = accountNumber,
        accountId = accountId ?: quota?.accountId
    )

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
                    severity = window.severity
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
}
