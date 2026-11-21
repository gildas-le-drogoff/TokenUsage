package fr.gildasledrogoff.tokenusage.feature.dashboard

import fr.gildasledrogoff.tokenusage.core.data.FetchRateLimiter
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.CachedQuotaResult
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchSnapshot
import fr.gildasledrogoff.tokenusage.core.provider.CredentialForm
import fr.gildasledrogoff.tokenusage.core.provider.ProviderRegistry
import fr.gildasledrogoff.tokenusage.core.provider.QuotaProvider
import fr.gildasledrogoff.tokenusage.core.provider.apiKeyForm
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import fr.gildasledrogoff.tokenusage.feature.settings.FakeDisplayPreferences
import fr.gildasledrogoff.tokenusage.feature.settings.inMemoryPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    /** Provider with in-memory accounts; each account's quota result is scripted by id. */
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

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        providers = AiService.entries.associateWith { FakeProvider(it) }
        lastFetch = FakeLastFetch()
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
        displayPreferences = FakeDisplayPreferences(),
        lastFetchRepository = lastFetch,
        rateLimiter = FetchRateLimiter(clock),
        clock = clock
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
}
