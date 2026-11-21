package fr.gildasledrogoff.tokenusage.core.data

import android.app.Application
import android.os.Build
import fr.gildasledrogoff.tokenusage.core.alert.TestClock
import fr.gildasledrogoff.tokenusage.core.database.DaoTestBase
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class BalanceHistoryRepositoryImplTest : DaoTestBase() {
    private val clock = TestClock(Instant.parse("2026-11-01T12:00:00Z"))
    private val repository get() = BalanceHistoryRepositoryImpl(db.balanceSnapshotDao(), clock)

    private suspend fun record(total: Double, accountId: String = "a", currency: String = "USD") =
        repository.record(AiService.DEEPSEEK, accountId, "Label $accountId", currency, total)

    @Test
    fun `latest sample is null without history`() = runTest {
        assertNull(repository.latestSample(AiService.DEEPSEEK, "a"))
    }

    @Test
    fun `latest sample returns the most recent record of the account`() = runTest {
        record(10.0)
        clock.advance(Duration.ofHours(1))
        record(8.0)
        record(99.0, accountId = "b")

        val sample = repository.latestSample(AiService.DEEPSEEK, "a")!!

        assertEquals(8.0, sample.totalBalance, 1e-9)
        assertEquals("Label a", sample.accountLabel)
        assertEquals("USD", sample.currency)
        assertEquals(clock.now, sample.timestamp)
    }

    @Test
    fun `samples older than the retention are purged on record`() = runTest {
        record(10.0)
        clock.advance(Duration.ofDays(BalanceHistoryRepositoryImpl.RETENTION_DAYS + 1L))
        record(5.0)

        assertEquals(1, db.balanceSnapshotDao().allSnapshots().size)
    }

    @Test
    fun `daily consumption sums balance decreases per day`() = runTest {
        record(10.0)
        clock.advance(Duration.ofHours(1))
        record(7.0)
        clock.advance(Duration.ofDays(1))
        record(8.0)
        clock.advance(Duration.ofHours(1))
        record(6.0)

        val points = repository.consumption(AiService.DEEPSEEK, "a", ConsumptionGranularity.DAILY)

        assertEquals(
            listOf(ConsumptionPoint("2026-11-01", 3.0, "USD"), ConsumptionPoint("2026-11-02", 2.0, "USD")),
            points
        )
    }

    @Test
    fun `daily consumption ignores samples outside its window`() = runTest {
        record(100.0)
        clock.advance(Duration.ofDays(40))
        record(10.0)
        clock.advance(Duration.ofHours(1))
        record(9.0)

        val daily = repository.consumption(AiService.DEEPSEEK, "a", ConsumptionGranularity.DAILY)
        val monthly = repository.consumption(AiService.DEEPSEEK, "a", ConsumptionGranularity.MONTHLY)

        assertEquals(listOf(1.0), daily.map { it.consumed })
        assertEquals(listOf(ConsumptionPoint("2026-12", 91.0, "USD")), monthly)
    }
}
