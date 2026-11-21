package fr.gildasledrogoff.tokenusage.feature.history

import fr.gildasledrogoff.tokenusage.core.alert.AlertPreferencesStore
import fr.gildasledrogoff.tokenusage.core.alert.BalanceSeriesSource
import fr.gildasledrogoff.tokenusage.core.alert.DepletionPredictor
import fr.gildasledrogoff.tokenusage.core.alert.TestClock
import fr.gildasledrogoff.tokenusage.core.alert.TimedValue
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.BalanceSample
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionPoint
import fr.gildasledrogoff.tokenusage.core.domain.model.DeepseekAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class BalanceHistoryViewModelTest {

    private val clock = TestClock()
    private val prefs = inMemoryPrefsManager()
    private val demoData = mock(DemoDataRepository::class.java)
    private val alertStore = AlertPreferencesStore { InMemorySharedPreferences() }

    private var series = emptyList<TimedValue>()
    private val balanceSeries = object : BalanceSeriesSource {
        override suspend fun series(service: AiService, accountId: String, since: Instant) = series
    }
    private val history = object : BalanceHistoryRepository {
        override suspend fun record(
            service: AiService, accountId: String, accountLabel: String, currency: String, totalBalance: Double
        ) = Unit

        override suspend fun latestSample(service: AiService, accountId: String) =
            BalanceSample(accountId, "Clé", "USD", 8.0, clock.now)

        override suspend fun consumption(
            service: AiService, accountId: String, granularity: ConsumptionGranularity
        ): List<ConsumptionPoint> = emptyList()
    }
    private val rates = object : ExchangeRateRepository {
        override suspend fun displayRate(currency: String): FxRate? = null
        override suspend fun fetchLatest(source: FxRateSource, from: String, to: String): Result<FxRate, AppError> =
            Result.Failure(AppError.NetworkError("offline"))

        override fun cachedRate(source: FxRateSource, from: String, to: String): FxRate? = null
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        `when`(demoData.isEnabled()).thenReturn(false)
        prefs.saveDeepseekAccounts(listOf(DeepseekAccount("d1", "Clé", "sk-x")))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = BalanceHistoryViewModel(
        history, prefs, demoData, rates, balanceSeries, DepletionPredictor(clock), alertStore, clock
    )

    private fun hoursAgo(h: Long) = clock.now.minus(Duration.ofHours(h))

    @Test
    fun `the estimated depletion date comes from the recorded history`() {
        series = listOf(TimedValue(38.0, hoursAgo(72)), TimedValue(28.0, hoursAgo(48)), TimedValue(18.0, hoursAgo(24)), TimedValue(8.0, hoursAgo(0)))

        val state = viewModel().uiState.value

        assertTrue(state.depletionEstimated)
        assertNotNull(state.depletionAt)
        // 10 per day, 8 left: 19.2 h from now.
        assertEquals(19.2, Duration.between(clock.now, state.depletionAt).toMinutes() / 60.0, 0.1)
    }

    @Test
    fun `no estimate when the history is too short`() {
        series = listOf(TimedValue(8.0, hoursAgo(0)))

        val state = viewModel().uiState.value

        assertTrue(state.depletionEstimated)
        assertNull(state.depletionAt)
    }

    @Test
    fun `no estimate when the balance is not decreasing`() {
        series = listOf(TimedValue(8.0, hoursAgo(48)), TimedValue(8.0, hoursAgo(24)), TimedValue(8.0, hoursAgo(0)))

        assertNull(viewModel().uiState.value.depletionAt)
    }

    @Test
    fun `sample data never shows an estimate`() {
        `when`(demoData.isEnabled()).thenReturn(true)
        `when`(demoData.sampleDeepseekAccounts()).thenReturn(listOf(DeepseekAccount("demo", "Démo", "x")))
        `when`(demoData.history).thenReturn(history)
        series = listOf(TimedValue(38.0, hoursAgo(48)), TimedValue(8.0, hoursAgo(0)))

        val state = viewModel().uiState.value

        assertFalse(state.depletionEstimated)
        assertNull(state.depletionAt)
    }
}
