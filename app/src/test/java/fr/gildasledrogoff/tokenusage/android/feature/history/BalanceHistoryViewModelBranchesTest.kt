package fr.gildasledrogoff.tokenusage.feature.history

import fr.gildasledrogoff.tokenusage.core.alert.AlertPreferencesStore
import fr.gildasledrogoff.tokenusage.core.alert.BalanceSeriesSource
import fr.gildasledrogoff.tokenusage.core.alert.DepletionPredictor
import fr.gildasledrogoff.tokenusage.core.alert.TestClock
import fr.gildasledrogoff.tokenusage.core.alert.TimedValue
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.BalanceSample
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionPoint
import fr.gildasledrogoff.tokenusage.core.domain.model.DeepseekAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import fr.gildasledrogoff.tokenusage.core.security.InMemorySharedPreferences
import fr.gildasledrogoff.tokenusage.feature.settings.inMemoryPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class BalanceHistoryViewModelBranchesTest {
    private val clock = TestClock()
    private val prefs = inMemoryPrefsManager()
    private val demoData = mock(DemoDataRepository::class.java)
    private val alertStore = AlertPreferencesStore { InMemorySharedPreferences() }

    private var latest: BalanceSample? = BalanceSample("d1", "Clé", "USD", 8.0, clock.now)
    private var points = listOf(ConsumptionPoint("2026-11-20", 2.0, "USD"), ConsumptionPoint("2026-11-21", 3.0, "EUR"))
    private var seriesFailure: Exception? = null
    private var rate: FxRate? = null
    private val consumptionCalls = mutableListOf<Pair<String, ConsumptionGranularity>>()

    private val balanceSeries = object : BalanceSeriesSource {
        override suspend fun series(service: AiService, accountId: String, since: java.time.Instant): List<TimedValue> {
            seriesFailure?.let { throw it }
            return emptyList()
        }
    }
    private val history = object : BalanceHistoryRepository {
        override suspend fun record(
            service: AiService, accountId: String, accountLabel: String, currency: String, totalBalance: Double
        ) = Unit
        override suspend fun latestSample(service: AiService, accountId: String) = latest
        override suspend fun consumption(
            service: AiService, accountId: String, granularity: ConsumptionGranularity
        ): List<ConsumptionPoint> {
            consumptionCalls += accountId to granularity
            return points
        }
    }
    private val rates = object : ExchangeRateRepository {
        override suspend fun displayRate(currency: String): FxRate? = rate
        override suspend fun fetchLatest(source: FxRateSource, from: String, to: String): Result<FxRate, AppError> =
            Result.Failure(AppError.NetworkError("offline"))
        override fun cachedRate(source: FxRateSource, from: String, to: String): FxRate? = null
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        `when`(demoData.isEnabled()).thenReturn(false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = BalanceHistoryViewModel(
        history, prefs, demoData, rates, balanceSeries, DepletionPredictor(clock), alertStore, clock
    )

    private fun twoAccounts() =
        prefs.saveDeepseekAccounts(listOf(DeepseekAccount("d1", "A", "sk-1"), DeepseekAccount("d2", "B", "sk-2")))

    @Test
    fun `without account the state is empty and no longer loading`() {
        val state = viewModel().uiState.value
        assertTrue(state.accounts.isEmpty())
        assertNull(state.selectedAccountId)
        assertFalse(state.isLoading)
        assertTrue(consumptionCalls.isEmpty())
    }

    @Test
    fun `the first account is selected by default and its history is loaded`() {
        twoAccounts()
        val state = viewModel().uiState.value
        assertEquals("d1", state.selectedAccountId)
        assertEquals(2, state.accounts.size)
        assertEquals(ConsumptionGranularity.DAILY, state.granularity)
        assertEquals(8.0, state.latestBalance!!, 0.0)
        assertEquals("USD", state.currency)
        assertEquals(points, state.points)
        assertNull(state.fxRate)
        assertFalse(state.isLoading)
    }

    @Test
    fun `selecting another account reloads for that account`() {
        twoAccounts()
        val vm = viewModel()
        vm.selectAccount("d2")
        assertEquals("d2", vm.uiState.value.selectedAccountId)
        assertEquals("d2", consumptionCalls.last().first)
    }

    @Test
    fun `selecting the current account does not reload`() {
        twoAccounts()
        val vm = viewModel()
        val calls = consumptionCalls.size
        vm.selectAccount("d1")
        assertEquals(calls, consumptionCalls.size)
    }

    @Test
    fun `an unknown selected account falls back to the first one`() {
        twoAccounts()
        val vm = viewModel()
        vm.selectAccount("ghost")
        assertEquals("d1", vm.uiState.value.selectedAccountId)
    }

    @Test
    fun `changing granularity reloads and keeps it`() {
        twoAccounts()
        val vm = viewModel()
        vm.selectGranularity(ConsumptionGranularity.MONTHLY)
        assertEquals(ConsumptionGranularity.MONTHLY, vm.uiState.value.granularity)
        assertEquals("d1" to ConsumptionGranularity.MONTHLY, consumptionCalls.last())
        val calls = consumptionCalls.size
        vm.selectGranularity(ConsumptionGranularity.MONTHLY)
        assertEquals(calls, consumptionCalls.size)
    }

    @Test
    fun `refresh reloads the data`() {
        twoAccounts()
        val vm = viewModel()
        val calls = consumptionCalls.size
        latest = BalanceSample("d1", "Clé", "USD", 5.0, clock.now)
        vm.refresh()
        assertEquals(calls + 1, consumptionCalls.size)
        assertEquals(5.0, vm.uiState.value.latestBalance!!, 0.0)
    }

    @Test
    fun `currency falls back to the last point then to empty`() {
        twoAccounts()
        latest = null
        assertEquals("EUR", viewModel().uiState.value.currency)
        assertNull(viewModel().uiState.value.latestBalance)
        points = emptyList()
        assertEquals("", viewModel().uiState.value.currency)
    }

    @Test
    fun `a display rate converts the balance and the points of the account currency only`() {
        twoAccounts()
        rate = FxRate("USD", "EUR", 0.5, FxRateSource.MANUAL)
        val state = viewModel().uiState.value
        assertEquals(rate, state.fxRate)
        assertEquals("EUR", state.currency)
        assertEquals(4.0, state.latestBalance!!, 0.0)
        assertEquals(ConsumptionPoint("2026-11-20", 1.0, "EUR"), state.points[0])
        assertEquals(ConsumptionPoint("2026-11-21", 3.0, "EUR"), state.points[1])
    }

    @Test
    fun `a failing series source does not break the screen`() {
        twoAccounts()
        seriesFailure = IllegalStateException("db")
        val state = viewModel().uiState.value
        assertFalse(state.isLoading)
        assertNull(state.depletionAt)
        assertTrue(state.depletionEstimated)
        assertEquals(points, state.points)
    }
}
