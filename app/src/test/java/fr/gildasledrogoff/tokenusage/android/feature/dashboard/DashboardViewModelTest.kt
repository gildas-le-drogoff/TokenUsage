package fr.gildasledrogoff.tokenusage.feature.dashboard
import fr.gildasledrogoff.tokenusage.core.data.FetchRateLimiter
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.DashboardInsight
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.InsightVisibility
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.CachedQuotaResult
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchSnapshot
import fr.gildasledrogoff.tokenusage.core.network.NetworkMonitor
import fr.gildasledrogoff.tokenusage.core.provider.CredentialForm
import fr.gildasledrogoff.tokenusage.core.provider.ProviderRegistry
import fr.gildasledrogoff.tokenusage.core.provider.QuotaProvider
import fr.gildasledrogoff.tokenusage.core.provider.apiKeyForm
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import fr.gildasledrogoff.tokenusage.core.workmanager.FakeReconnectRefresh
import fr.gildasledrogoff.tokenusage.feature.settings.FakeDisplayPreferences
import fr.gildasledrogoff.tokenusage.feature.settings.inMemoryPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    
    private class FakeProvider(override val service: AiService) : QuotaProvider {
        override val form: CredentialForm =
            apiKeyForm(labelRes = 1, fieldHintRes = 2) { Credential.ApiKeyCredential(it) }
        val accounts = mutableListOf<ServiceAccount>()
        val results = mutableMapOf<String, Result<QuotaInfo, AppError>>()
        var fetchCount = 0
        override fun accounts(): List<ServiceAccount> = accounts.toList()
        override suspend fun fetchQuota(account: ServiceAccount): Result<QuotaInfo, AppError> {
            fetchCount++
            return results[account.id]
                ?: Result.Success(QuotaInfo(service, emptyList(), accountId = account.id))
        }
    }
    private class FakeLastFetch : LastFetchRepository {
        var stored: LastFetchSnapshot? = null
        override suspend fun snapshot(): LastFetchSnapshot? = stored
        override suspend fun record(fetchedAt: Instant, results: List<CachedQuotaResult>) {
            stored = LastFetchSnapshot(fetchedAt, results)
        }
    }
    private class NoExchangeRates : ExchangeRateRepository {
        override suspend fun displayRate(currency: String): FxRate? = null
        override suspend fun fetchLatest(source: FxRateSource, from: String, to: String): Result<FxRate, AppError> =
            Result.Failure(AppError.NetworkError("offline"))
        override fun cachedRate(source: FxRateSource, from: String, to: String): FxRate? = null
    }
    private val now = Instant.parse("2026-11-21T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var providers: Map<AiService, FakeProvider>
    private lateinit var lastFetch: FakeLastFetch
    private val quotaEvents = mock(fr.gildasledrogoff.tokenusage.core.alert.QuotaEventDispatcher::class.java)
    private lateinit var displayPreferences: FakeDisplayPreferences
    private var online = true
    private val networkChanges = MutableSharedFlow<Boolean>()
    private lateinit var reconnectRefresh: FakeReconnectRefresh
    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        providers = AiService.entries.associateWith { FakeProvider(it) }
        lastFetch = FakeLastFetch()
        displayPreferences = FakeDisplayPreferences()
        online = true
        reconnectRefresh = FakeReconnectRefresh()
    }
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }
    private fun viewModel() = DashboardViewModel(
        providerRegistry = ProviderRegistry(providers.values.toSet()),
        prefsManager = inMemoryPrefsManager(),
        demoData = mock(DemoDataRepository::class.java),
        exchangeRates = NoExchangeRates(),
        widgetQuotaPublisher = mock(WidgetQuotaPublisher::class.java),
        balanceHistory = mock(BalanceHistoryRepository::class.java),
        displayPreferences = displayPreferences,
        lastFetchRepository = lastFetch,
        rateLimiter = FetchRateLimiter(clock),
        clock = clock,
        statusRepository = mock(fr.gildasledrogoff.tokenusage.core.status.ProviderStatusRepository::class.java),
        celebrationBus = fr.gildasledrogoff.tokenusage.core.alert.ResetCelebrationBus(),
        balanceSeries = mock(fr.gildasledrogoff.tokenusage.core.alert.BalanceSeriesSource::class.java),
        quotaEvents = quotaEvents,
        networkMonitor = object : NetworkMonitor {
            override fun isOnline(): Boolean = online
            override fun onlineChanges(): Flow<Boolean> = networkChanges
        },
        reconnectRefresh = reconnectRefresh
    )
    private fun addAccount(service: AiService, id: String, label: String = "") {
        providers.getValue(service).accounts += ServiceAccount(id, label, Credential.ApiKeyCredential("key-$id"))
    }
    @Test
    fun `no account configured gives an empty success`() = runTest(dispatcher) {
        val state = viewModel().uiState.value
        assertEquals(DashboardUiState.Success(emptyList(), now), state)
    }
    @Test
    fun `every account of a service gets its own card`() = runTest(dispatcher) {
        addAccount(AiService.CODEX, "c1", "Work")
        addAccount(AiService.CODEX, "c2")
        addAccount(AiService.OPENAI, "o1")
        val state = viewModel().uiState.value as DashboardUiState.Success
        val codexCards = state.cards.filter { it.service == AiService.CODEX }
        assertEquals(setOf("c1", "c2"), codexCards.map { it.accountId }.toSet())
        assertEquals("Work", codexCards.single { it.accountId == "c1" }.accountLabel)
        assertEquals(2, codexCards.single { it.accountId == "c2" }.accountNumber)
        assertEquals(listOf("o1"), state.cards.filter { it.service == AiService.OPENAI }.map { it.accountId })
    }
    @Test
    fun `results are recorded in the last fetch cache with their account id`() = runTest(dispatcher) {
        addAccount(AiService.GEMINI, "g1")
        addAccount(AiService.GEMINI, "g2")
        viewModel()
        val recorded = lastFetch.stored!!
        assertEquals(now, recorded.fetchedAt)
        assertEquals(setOf("g1", "g2"), recorded.results!!.map { it.accountId }.toSet())
    }
    @Test
    fun `one failing account among several gives a partial success`() = runTest(dispatcher) {
        addAccount(AiService.COPILOT, "p1")
        addAccount(AiService.COPILOT, "p2")
        providers.getValue(AiService.COPILOT).results["p2"] = Result.Failure(AppError.RateLimited)
        val state = viewModel().uiState.value as DashboardUiState.PartialSuccess
        assertEquals(AppError.RateLimited, state.errors[AiService.COPILOT])
        assertEquals(AppError.RateLimited, state.cards.single { it.accountId == "p2" }.error)
        assertEquals(null, state.cards.single { it.accountId == "p1" }.error)
    }
    @Test
    fun `all accounts failing gives an error state`() = runTest(dispatcher) {
        addAccount(AiService.OPENROUTER, "r1")
        providers.getValue(AiService.OPENROUTER).results["r1"] = Result.Failure(AppError.RateLimited)
        assertEquals(DashboardUiState.Error(AppError.RateLimited), viewModel().uiState.value)
    }
    @Test
    fun `a recent fetch is served from the cache without hitting the services`() = runTest(dispatcher) {
        addAccount(AiService.CODEX, "c1")
        val cachedQuota = QuotaInfo(AiService.CODEX, emptyList(), accountId = "c1")
        lastFetch.stored = LastFetchSnapshot(
            fetchedAt = now.minusSeconds(5),
            results = listOf(CachedQuotaResult(AiService.CODEX, quota = cachedQuota, error = null, accountId = "c1"))
        )
        val state = viewModel().uiState.value as DashboardUiState.Success
        assertEquals(0, providers.getValue(AiService.CODEX).fetchCount)
        assertEquals(now.minusSeconds(5), state.lastUpdated)
        assertEquals(listOf("c1"), state.cards.map { it.accountId })
    }
    @Test
    fun `an old cache does not prevent a new fetch`() = runTest(dispatcher) {
        addAccount(AiService.CODEX, "c1")
        lastFetch.stored = LastFetchSnapshot(now.minusSeconds(3600), results = emptyList())
        viewModel()
        assertEquals(1, providers.getValue(AiService.CODEX).fetchCount)
        assertTrue(lastFetch.stored!!.fetchedAt == now)
    }

    private val anHourAgo = now.minusSeconds(3600)
    private fun quota(service: AiService, id: String, utilization: Double, at: Instant) =
        QuotaInfo(service, listOf(UsageWindow("5h", utilization, resetsAt = null)), fetchedAt = at, accountId = id)
    private fun cached(
        service: AiService,
        id: String,
        utilization: Double,
        at: Instant = anHourAgo,
        error: AppError? = null
    ) = CachedQuotaResult(service, quota = quota(service, id, utilization, at), error = error, accountId = id)
    private fun cache(at: Instant, vararg results: CachedQuotaResult) {
        lastFetch.stored = LastFetchSnapshot(at, results.toList())
    }
    private fun cards(state: DashboardUiState): List<ServiceCardData> = when (state) {
        is DashboardUiState.Success -> state.cards
        is DashboardUiState.PartialSuccess -> state.cards
        is DashboardUiState.Offline -> state.cards
        else -> error("No cards in $state")
    }
    private fun DashboardUiState.card(id: String) = cards(this).single { it.accountId == id }


    @Test
    fun `offline with saved data shows the last data instead of an error`() = runTest(dispatcher) {
        addAccount(AiService.CODEX, "c1")
        addAccount(AiService.OPENAI, "o1")
        online = false
        cache(
            anHourAgo,
            cached(AiService.CODEX, "c1", 0.4, at = anHourAgo),
            cached(AiService.OPENAI, "o1", 0.1, at = now.minusSeconds(7200))
        )
        val state = viewModel().uiState.value as DashboardUiState.Offline
        assertEquals(0, providers.getValue(AiService.CODEX).fetchCount)
        assertEquals(0, providers.getValue(AiService.OPENAI).fetchCount)
        assertEquals("the most recent data date is shown", anHourAgo, state.lastUpdated)
        val codex = state.card("c1")
        assertEquals(anHourAgo, codex.staleSince)
        assertEquals(0.4, codex.windows.single().utilization, 0.0)
        assertTrue(codex.hasData)
        assertEquals(now.minusSeconds(7200), state.card("o1").staleSince)
        assertEquals("nothing new is recorded", anHourAgo, lastFetch.stored!!.fetchedAt)
    }

    @Test
    fun `offline without saved data gives an offline error`() = runTest(dispatcher) {
        addAccount(AiService.CODEX, "c1")
        online = false
        val state = viewModel().uiState.value as DashboardUiState.Error
        assertTrue(state.offline)
        assertEquals(0, providers.getValue(AiService.CODEX).fetchCount)
    }

    @Test
    fun `offline with no account configured gives an empty success`() = runTest(dispatcher) {
        online = false
        assertEquals(DashboardUiState.Success(emptyList(), now), viewModel().uiState.value)
    }

    @Test
    fun `offline, an account never fetched gets an error card next to the saved ones`() = runTest(dispatcher) {
        addAccount(AiService.CODEX, "c1")
        addAccount(AiService.CODEX, "c2")
        online = false
        cache(anHourAgo, cached(AiService.CODEX, "c1", 0.4))
        val state = viewModel().uiState.value as DashboardUiState.Offline
        assertEquals(anHourAgo, state.card("c1").staleSince)
        val fresh = state.card("c2")
        assertTrue(fresh.error is AppError.NetworkError)
        assertNull(fresh.staleSince)
        assertFalse(fresh.hasData)
    }


    @Test
    fun `the dashboard refreshes on its own when the network returns`() = runTest(dispatcher) {
        addAccount(AiService.CODEX, "c1")
        online = false
        cache(anHourAgo, cached(AiService.CODEX, "c1", 0.4))
        val vm = viewModel()
        assertTrue(vm.uiState.value is DashboardUiState.Offline)
        backgroundScope.launch { vm.observeConnectivity() }
        online = true
        networkChanges.emit(true)
        advanceTimeBy(1_000)
        assertEquals("waits for the network to settle", 0, providers.getValue(AiService.CODEX).fetchCount)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, providers.getValue(AiService.CODEX).fetchCount)
        assertTrue(vm.uiState.value is DashboardUiState.Success)
        assertEquals(1, reconnectRefresh.offlineSignals)
        assertEquals("the background reconnect refresh is no longer needed", 1, reconnectRefresh.onlineSignals)
    }

    @Test
    fun `the network returning also retries an offline error`() = runTest(dispatcher) {
        addAccount(AiService.CODEX, "c1")
        online = false
        val vm = viewModel()
        assertTrue((vm.uiState.value as DashboardUiState.Error).offline)
        backgroundScope.launch { vm.observeConnectivity() }
        online = true
        networkChanges.emit(true)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, providers.getValue(AiService.CODEX).fetchCount)
        assertTrue(vm.uiState.value is DashboardUiState.Success)
    }

    @Test
    fun `a network change does not refetch fresh data`() = runTest(dispatcher) {
        addAccount(AiService.CODEX, "c1")
        val vm = viewModel()
        assertEquals(1, providers.getValue(AiService.CODEX).fetchCount)
        backgroundScope.launch { vm.observeConnectivity() }
        networkChanges.emit(false)
        networkChanges.emit(true)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, providers.getValue(AiService.CODEX).fetchCount)
    }

    @Test
    fun `a network lost again before settling does not trigger a refresh`() = runTest(dispatcher) {
        addAccount(AiService.CODEX, "c1")
        online = false
        cache(anHourAgo, cached(AiService.CODEX, "c1", 0.4))
        val vm = viewModel()
        backgroundScope.launch { vm.observeConnectivity() }
        networkChanges.emit(true)
        advanceTimeBy(500)
        networkChanges.emit(false)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, providers.getValue(AiService.CODEX).fetchCount)
        assertTrue(vm.uiState.value is DashboardUiState.Offline)
    }

    @Test
    fun `every account failing for lack of connectivity asks for a reconnect refresh`() = runTest(dispatcher) {
        addAccount(AiService.CODEX, "c1")
        providers.getValue(AiService.CODEX).results["c1"] =
            Result.Failure(AppError.NetworkError("dns", UnknownHostException("chatgpt.com")))
        viewModel()
        assertEquals(1, reconnectRefresh.offlineSignals)
        assertEquals(0, reconnectRefresh.onlineSignals)
    }

    @Test
    fun `every account failing for lack of connectivity is treated as offline`() = runTest(dispatcher) {
        addAccount(AiService.CODEX, "c1")
        providers.getValue(AiService.CODEX).results["c1"] =
            Result.Failure(AppError.NetworkError("dns", UnknownHostException("chatgpt.com")))
        cache(anHourAgo, cached(AiService.CODEX, "c1", 0.4))
        val state = viewModel().uiState.value as DashboardUiState.Offline
        assertEquals(1, providers.getValue(AiService.CODEX).fetchCount)
        assertEquals(anHourAgo, state.card("c1").staleSince)
    }


    @Test
    fun `one unreachable provider shows its last data flagged with the error`() = runTest(dispatcher) {
        addAccount(AiService.CODEX, "c1")
        addAccount(AiService.OPENROUTER, "r1")
        providers.getValue(AiService.OPENROUTER).results["r1"] = Result.Failure(AppError.ServiceUnavailable)
        cache(anHourAgo, cached(AiService.CODEX, "c1", 0.2), cached(AiService.OPENROUTER, "r1", 0.7))
        val state = viewModel().uiState.value as DashboardUiState.PartialSuccess
        assertEquals(mapOf(AiService.OPENROUTER to AppError.ServiceUnavailable), state.errors)
        assertEquals(now, state.lastUpdated)
        val failing = state.card("r1")
        assertEquals(AppError.ServiceUnavailable, failing.error)
        assertEquals(anHourAgo, failing.staleSince)
        assertEquals(0.7, failing.windows.single().utilization, 0.0)
        val healthy = state.card("c1")
        assertNull(healthy.error)
        assertNull(healthy.staleSince)
    }

    @Test
    fun `the last known quota stays in the cache after a failure`() = runTest(dispatcher) {
        addAccount(AiService.OPENROUTER, "r1")
        providers.getValue(AiService.OPENROUTER).results["r1"] = Result.Failure(AppError.ServiceUnavailable)
        cache(anHourAgo, cached(AiService.OPENROUTER, "r1", 0.7))
        viewModel()
        val stored = lastFetch.stored!!
        assertEquals(now, stored.fetchedAt)
        val entry = stored.results!!.single()
        assertEquals(AppError.ServiceUnavailable, entry.error)
        assertEquals(anHourAgo, entry.quota!!.fetchedAt)
    }

    @Test
    fun `all accounts failing with saved data gives a partial success rather than an error`() = runTest(dispatcher) {
        addAccount(AiService.OPENROUTER, "r1")
        providers.getValue(AiService.OPENROUTER).results["r1"] = Result.Failure(AppError.RateLimited)
        cache(anHourAgo, cached(AiService.OPENROUTER, "r1", 0.7))
        val state = viewModel().uiState.value as DashboardUiState.PartialSuccess
        assertEquals(AppError.RateLimited, state.card("r1").error)
        assertEquals(anHourAgo, state.card("r1").staleSince)
    }

    @Test
    fun `a stale entry served from the cache during the cooldown keeps its warning`() = runTest(dispatcher) {
        addAccount(AiService.OPENROUTER, "r1")
        cache(now.minusSeconds(5), cached(AiService.OPENROUTER, "r1", 0.7, error = AppError.ServiceUnavailable))
        val state = viewModel().uiState.value as DashboardUiState.PartialSuccess
        assertEquals(0, providers.getValue(AiService.OPENROUTER).fetchCount)
        assertEquals(AppError.ServiceUnavailable, state.card("r1").error)
        assertEquals(anHourAgo, state.card("r1").staleSince)
    }

    @Test
    fun `a recovered account drops its stale marker`() = runTest(dispatcher) {
        addAccount(AiService.OPENROUTER, "r1")
        cache(anHourAgo, cached(AiService.OPENROUTER, "r1", 0.7, error = AppError.ServiceUnavailable))
        val state = viewModel().uiState.value as DashboardUiState.Success
        assertNull(state.card("r1").staleSince)
        assertNull(lastFetch.stored!!.results!!.single().error)
    }


    @Test
    fun `hidden pace and curves are applied per provider and per account`() = runTest(dispatcher) {
        addAccount(AiService.CODEX, "c1")
        addAccount(AiService.OPENAI, "o1")
        addAccount(AiService.OPENAI, "o2")
        displayPreferences.setInsightVisibility(
            InsightVisibility()
                .with(DashboardInsight.PACE, AiService.CODEX, null, visible = false)
                .with(DashboardInsight.CURVES, AiService.OPENAI, "o1", visible = false)
        )
        val state = viewModel().uiState.value
        assertFalse(state.card("c1").showPace)
        assertTrue(state.card("c1").showCurves)
        assertTrue(state.card("o1").showPace)
        assertFalse(state.card("o1").showCurves)
        assertTrue(state.card("o2").showPace)
        assertTrue(state.card("o2").showCurves)
    }

    @Test
    fun `changing the visibility updates the cards on screen without fetching again`() = runTest(dispatcher) {
        addAccount(AiService.CODEX, "c1")
        val viewModel = viewModel()
        assertTrue(viewModel.uiState.value.card("c1").showCurves)
        displayPreferences.setInsightVisibility(
            InsightVisibility().with(DashboardInsight.CURVES, AiService.CODEX, "c1", visible = false)
        )
        assertFalse(viewModel.uiState.value.card("c1").showCurves)
        assertEquals(1, providers.getValue(AiService.CODEX).fetchCount)
    }

    @Test
    fun `visibility also applies to the last data shown offline`() = runTest(dispatcher) {
        addAccount(AiService.CODEX, "c1")
        online = false
        cache(anHourAgo, cached(AiService.CODEX, "c1", 0.4))
        displayPreferences.setInsightVisibility(
            InsightVisibility().with(DashboardInsight.PACE, AiService.CODEX, null, visible = false)
        )
        assertFalse(viewModel().uiState.value.card("c1").showPace)
    }

    @Test
    fun `states without cards are left untouched by the visibility`() {
        val hidden = InsightVisibility().with(DashboardInsight.PACE, AiService.CODEX, null, visible = false)
        assertEquals(DashboardUiState.Loading, DashboardUiState.Loading.withInsightVisibility(hidden))
        val error = DashboardUiState.Error(AppError.RateLimited)
        assertEquals(error, error.withInsightVisibility(hidden))
    }
}
