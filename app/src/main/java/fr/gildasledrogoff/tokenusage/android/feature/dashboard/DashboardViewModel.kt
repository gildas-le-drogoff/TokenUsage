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
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.CachedQuotaResult
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ClaudeQuotaRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchSnapshot
import fr.gildasledrogoff.tokenusage.core.domain.repository.MultiAccountQuotaRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.QuotaRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ZaiRepository
import fr.gildasledrogoff.tokenusage.core.data.FetchRateLimiter
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import fr.gildasledrogoff.tokenusage.di.ClaudeRepository
import fr.gildasledrogoff.tokenusage.di.CodexRepository
import fr.gildasledrogoff.tokenusage.di.CopilotRepository
import fr.gildasledrogoff.tokenusage.di.DeepseekRepository
import fr.gildasledrogoff.tokenusage.di.GeminiRepository
import fr.gildasledrogoff.tokenusage.di.OpenAiRepository
import fr.gildasledrogoff.tokenusage.di.OpenRouterRepository
import fr.gildasledrogoff.tokenusage.di.ZaiRepositoryQualifier
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


private val singleKeyServices = AiService.entries.filter { it.usesSingleApiKey }


private data class FetchOutcome(
    val service: AiService,
    val accountLabel: String?,
    val result: Result<QuotaInfo, AppError>,
    val accountNumber: Int? = null
)


private fun accountNaming(label: String, index: Int, count: Int): Pair<String?, Int?> {
    val named = label.takeIf { it.isNotBlank() }
    return named to (if (named == null && count > 1) index + 1 else null)
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ClaudeRepository private val claudeRepository: ClaudeQuotaRepository,
    @CodexRepository private val codexRepository: QuotaRepository,
    @GeminiRepository private val geminiRepository: QuotaRepository,
    @DeepseekRepository private val deepseekRepository: MultiAccountQuotaRepository,
    @ZaiRepositoryQualifier private val zaiRepository: ZaiRepository,
    @CopilotRepository private val copilotRepository: QuotaRepository,
    @OpenRouterRepository private val openRouterRepository: QuotaRepository,
    @OpenAiRepository private val openAiRepository: QuotaRepository,
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
            val accounts = if (demo) demoData.sampleDeepseekAccounts() else prefsManager.getDeepseekAccounts()
            val history = if (demo) demoData.history else balanceHistory

            val charts = accounts.map { account ->
                val points = history.consumption(AiService.DEEPSEEK, account.id, ConsumptionGranularity.DAILY)
                val currency = points.lastOrNull()?.currency ?: ""
                val rate = exchangeRates.displayRate(currency)
                AccountConsumptionUi(
                    accountId = account.id,
                    label = account.label,
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
        prefsManager.getClaudeAccounts().joinToString(",") {
            "${it.id}:${it.label}:${it.credential.accessToken.hashCode()}"
        },
        prefsManager.hasCredential(AiService.CODEX),
        prefsManager.hasCredential(AiService.GEMINI),
        prefsManager.hasCredential(AiService.DEEPSEEK),
        prefsManager.hasCredential(AiService.ZAI),
        prefsManager.getZaiAccounts().joinToString(",") { "${it.id}:${it.label}:${it.apiKey.hashCode()}" },
        singleKeyServices.joinToString(",") { prefsManager.loadCredential(it)?.accessToken.orEmpty().hashCode().toString() },
        prefsManager.isZaiMonthlyToolsHidden(),
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
                val results = demoData.sampleQuotas().map { FetchOutcome(it.service, it.accountLabel, it.result) }
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
        val deferreds = buildList {
            val claudeAccounts = prefsManager.getClaudeAccounts()
            claudeAccounts.forEachIndexed { index, account ->
                val (label, number) = accountNaming(account.label, index, claudeAccounts.size)
                add(async {
                    FetchOutcome(AiService.CLAUDE, label, claudeRepository.fetchQuotaForAccount(account), number)
                })
            }
            if (prefsManager.hasCredential(AiService.CODEX)) {
                add(async { FetchOutcome(AiService.CODEX, null, codexRepository.fetchQuota()) })
            }
            if (prefsManager.hasCredential(AiService.GEMINI)) {
                add(async { FetchOutcome(AiService.GEMINI, null, geminiRepository.fetchQuota()) })
            }
            if (prefsManager.hasCredential(AiService.DEEPSEEK)) {
                add(async { FetchOutcome(AiService.DEEPSEEK, null, deepseekRepository.fetchQuota()) })
            }
            for ((service, repository) in singleKeyRepositories()) {
                if (prefsManager.hasCredential(service)) {
                    add(async { FetchOutcome(service, null, repository.fetchQuota()) })
                }
            }
            val zaiAccounts = prefsManager.getZaiAccounts()
            zaiAccounts.forEachIndexed { index, account ->
                val (label, number) = accountNaming(account.label, index, zaiAccounts.size)
                add(async {
                    FetchOutcome(AiService.ZAI, label, zaiRepository.fetchQuotaForAccount(account), number)
                })
            }
        }
        deferreds.awaitAll()
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
            val fresh = results.mapNotNull { (it.result as? Result.Success)?.value }
            viewModelScope.launch {
                runCatching { widgetQuotaPublisher.publish(fresh) }
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
                            .copy(accountNumber = outcome.accountNumber)
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
        quota = result.getOrNull(),
        error = result.errorOrNull()
    )

    private fun CachedQuotaResult.toFetchOutcome() = FetchOutcome(
        service = service,
        accountLabel = accountLabel,
        result = quota?.let { Result.Success(it) }
            ?: Result.Failure(error ?: AppError.NetworkError("No cached payload for this account")),
        accountNumber = accountNumber
    )

    private fun singleKeyRepositories(): List<Pair<AiService, QuotaRepository>> = listOf(
        AiService.COPILOT to copilotRepository,
        AiService.OPENROUTER to openRouterRepository,
        AiService.OPENAI to openAiRepository
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
