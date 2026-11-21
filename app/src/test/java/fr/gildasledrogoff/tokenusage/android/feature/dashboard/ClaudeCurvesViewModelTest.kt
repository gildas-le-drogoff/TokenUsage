package fr.gildasledrogoff.tokenusage.feature.dashboard

import fr.gildasledrogoff.tokenusage.core.alert.BalanceSeriesSource
import fr.gildasledrogoff.tokenusage.core.alert.TestClock
import fr.gildasledrogoff.tokenusage.core.alert.TimedValue
import fr.gildasledrogoff.tokenusage.core.data.WINDOW_5H
import fr.gildasledrogoff.tokenusage.core.data.WINDOW_7D
import fr.gildasledrogoff.tokenusage.core.data.claudeUtilizationKey
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoQuota
import fr.gildasledrogoff.tokenusage.core.domain.model.DeepseekAccount
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
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ClaudeCurvesViewModelTest {
    private val clock = TestClock()
    private val requested = mutableListOf<Pair<String, Instant>>()
    private var failure: Exception? = null
    private var demo = false

    private fun point(v: Double) = TimedValue(v, clock.now)

    private val series = object : BalanceSeriesSource {
        override suspend fun series(service: AiService, accountId: String, since: Instant): List<TimedValue> {
            failure?.let { throw it }
            assertEquals(AiService.CLAUDE, service)
            requested += accountId to since
            return if (accountId.endsWith(WINDOW_5H)) listOf(point(1.0), point(2.0)) else listOf(point(3.0))
        }
    }

    private val demoData = object : DemoDataRepository {
        override val isAvailable = true
        override fun isEnabled() = demo
        override fun setEnabled(enabled: Boolean) = Unit
        override val supportedServices = emptySet<AiService>()
        override suspend fun sampleQuotas() = emptyList<DemoQuota>()
        override fun sampleDeepseekAccounts() = emptyList<DeepseekAccount>()
        override val history: BalanceHistoryRepository = mock(BalanceHistoryRepository::class.java)
        override fun sampleUtilizationSeries(
            accountId: String, windowLabel: String, from: Instant, to: Instant
        ) = listOf(TimedValue(if (windowLabel == WINDOW_5H) 10.0 else 70.0, to), TimedValue(11.0, from))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ClaudeCurvesViewModel(series, demoData, clock)

    @Test
    fun `curves are empty before any load`() {
        assertNull(viewModel().curves.value)
    }

    @Test
    fun `load reads both windows over the last seven days`() {
        val vm = viewModel()
        vm.load("acc")
        val curves = vm.curves.value!!
        assertEquals(clock.now, curves.to)
        assertEquals(clock.now.minus(Duration.ofDays(7)), curves.from)
        assertEquals(2, curves.fiveHour.size)
        assertEquals(1, curves.sevenDay.size)
        assertEquals(
            listOf(claudeUtilizationKey("acc", WINDOW_5H), claudeUtilizationKey("acc", WINDOW_7D)),
            requested.map { it.first }
        )
        assertTrue(requested.all { it.second == curves.from })
    }

    @Test
    fun `demo mode uses the sample series instead of the recorded ones`() {
        demo = true
        val vm = viewModel()
        vm.load("acc")
        val curves = vm.curves.value!!
        assertTrue(requested.isEmpty())
        assertEquals(10.0, curves.fiveHour.first().value, 0.0)
        assertEquals(70.0, curves.sevenDay.first().value, 0.0)
    }

    @Test
    fun `a failing source gives empty curves`() {
        failure = IllegalStateException("db")
        val vm = viewModel()
        vm.load("acc")
        val curves = vm.curves.value
        assertNotNull(curves)
        assertTrue(curves!!.isEmpty)
        assertTrue(curves.fiveHour.isEmpty())
    }

    @Test
    fun `isEmpty needs fewer than two points in both windows`() {
        val t = clock.now
        val two = listOf(TimedValue(1.0, t), TimedValue(2.0, t))
        val one = listOf(TimedValue(1.0, t))
        assertTrue(ClaudeCurves(t, t, one, one).isEmpty)
        assertTrue(ClaudeCurves(t, t, emptyList(), emptyList()).isEmpty)
        assertFalse(ClaudeCurves(t, t, two, one).isEmpty)
        assertFalse(ClaudeCurves(t, t, one, two).isEmpty)
    }
}
