package fr.gildasledrogoff.tokenusage.core.demo
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
class SampleBalanceHistoryTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val now = Instant.parse("2026-11-15T18:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val specs = SampleDataRepository.DEEPSEEK_SPECS
    private val history = SampleBalanceHistory(clock, specs)
    private fun spec(id: String) = specs.first { it.account.id == id }
    @Test
    fun `generation is deterministic for the same clock`() {
        val main = spec("demo-deepseek-main")
        assertEquals(
            SampleBalanceHistory.generate(main, now, zone),
            SampleBalanceHistory.generate(main, now, zone)
        )
    }
    @Test
    fun `main account history contains top-ups`() {
        val samples = SampleBalanceHistory.generate(spec("demo-deepseek-main"), now, zone)
        val hasTopUp = samples.zipWithNext().any { (previous, next) -> next.totalBalance > previous.totalBalance }
        assertTrue(hasTopUp)
    }
    @Test
    fun `daily series has 30 days of data except the offline hole`() = runTest {
        val points = history.consumption(AiService.DEEPSEEK, spec("demo-deepseek-main").account.id, ConsumptionGranularity.DAILY)
        val days = points.map { it.periodKey }.toSet()
        val today = LocalDate.ofInstant(now, zone)
        assertTrue(points.size >= 20)
        assertTrue((10L..12L).none { today.minusDays(it).toString() in days })
        assertTrue(points.all { it.consumed >= 0.0 && it.currency == "CNY" })
    }
    @Test
    fun `monthly series covers a full year for the main account`() = runTest {
        val points = history.consumption(AiService.DEEPSEEK, spec("demo-deepseek-main").account.id, ConsumptionGranularity.MONTHLY)
        assertTrue(points.size >= 12)
    }
    @Test
    fun `account without history yields no sample`() = runTest {
        val id = spec("demo-deepseek-new").account.id
        assertNull(history.latestSample(AiService.DEEPSEEK, id))
        assertTrue(history.consumption(AiService.DEEPSEEK, id, ConsumptionGranularity.DAILY).isEmpty())
    }
    @Test
    fun `stopped account only has old monthly data`() = runTest {
        val id = spec("demo-deepseek-old").account.id
        assertTrue(history.consumption(AiService.DEEPSEEK, id, ConsumptionGranularity.DAILY).isEmpty())
        assertTrue(history.consumption(AiService.DEEPSEEK, id, ConsumptionGranularity.MONTHLY).isNotEmpty())
    }
}
