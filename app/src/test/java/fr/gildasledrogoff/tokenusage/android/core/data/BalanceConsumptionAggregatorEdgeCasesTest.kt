package fr.gildasledrogoff.tokenusage.core.data
import fr.gildasledrogoff.tokenusage.core.data.BalanceConsumptionAggregator.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class BalanceConsumptionAggregatorEdgeCasesTest {
    private val utc = ZoneId.of("UTC")
    private val paris = ZoneId.of("Europe/Paris")
    private fun sample(balance: Double, iso: String, currency: String = "EUR") =
        Sample(totalBalance = balance, currency = currency, timestamp = Instant.parse(iso))
    @Test
    fun `a single sample never produces consumption`() {
        assertTrue(BalanceConsumptionAggregator.daily(listOf(sample(10.0, "2026-11-15T08:00:00Z")), utc).isEmpty())
        assertTrue(BalanceConsumptionAggregator.monthly(listOf(sample(10.0, "2026-11-15T08:00:00Z")), utc).isEmpty())
    }
    @Test
    fun `two identical timestamps yield a zero consumption bucket`() {
        val points = BalanceConsumptionAggregator.daily(
            listOf(sample(10.0, "2026-11-15T08:00:00Z"), sample(7.0, "2026-11-15T08:00:00Z")),
            utc
        )
        assertEquals(1, points.size)
        assertEquals(3.0, points.single().consumed, 1e-9)
    }
    @Test
    fun `a flat balance still yields a zero bucket per day`() {
        val points = BalanceConsumptionAggregator.daily(
            listOf(
                sample(50.0, "2026-11-14T08:00:00Z"),
                sample(50.0, "2026-11-15T08:00:00Z"),
                sample(50.0, "2026-11-16T08:00:00Z")
            ),
            utc
        )
        assertEquals(listOf("2026-11-15", "2026-11-16"), points.map { it.periodKey })
        assertTrue(points.all { it.consumed == 0.0 })
    }
    @Test
    fun `a top up between two drops never produces negative consumption`() {
        val points = BalanceConsumptionAggregator.daily(
            listOf(
                sample(10.0, "2026-11-15T08:00:00Z"),
                sample(4.0, "2026-11-15T09:00:00Z"),
                sample(104.0, "2026-11-15T10:00:00Z"),
                sample(100.0, "2026-11-15T11:00:00Z")
            ),
            utc
        )
        assertEquals(10.0, points.single().consumed, 1e-9)
        assertTrue(points.all { it.consumed >= 0.0 })
    }
    @Test
    fun `a top up alone on a day yields a zero bucket`() {
        val points = BalanceConsumptionAggregator.daily(
            listOf(sample(1.0, "2026-11-14T08:00:00Z"), sample(500.0, "2026-11-15T08:00:00Z")),
            utc
        )
        assertEquals(0.0, points.single().consumed, 0.0)
    }
    @Test
    fun `consumption that exactly cancels a top up in the same interval is lost`() {
        val points = BalanceConsumptionAggregator.daily(
            listOf(sample(10.0, "2026-11-15T08:00:00Z"), sample(10.0, "2026-11-15T20:00:00Z")),
            utc
        )
        assertEquals(0.0, points.single().consumed, 0.0)
    }
    @Test
    fun `a multi day gap attributes the whole drop to the day of the later sample`() {
        val points = BalanceConsumptionAggregator.daily(
            listOf(sample(100.0, "2026-11-10T08:00:00Z"), sample(70.0, "2026-11-14T08:00:00Z")),
            utc
        )
        assertEquals(listOf("2026-11-14"), points.map { it.periodKey })
        assertEquals(30.0, points.single().consumed, 1e-9)
    }
    @Test
    fun `days without samples do not appear in the result and order is chronological`() {
        val points = BalanceConsumptionAggregator.daily(
            listOf(
                sample(100.0, "2026-11-01T08:00:00Z"),
                sample(90.0, "2026-11-02T08:00:00Z"),
                sample(80.0, "2026-11-20T08:00:00Z")
            ),
            utc
        )
        assertEquals(listOf("2026-11-02", "2026-11-20"), points.map { it.periodKey })
    }
    @Test
    fun `monthly buckets cross year boundaries`() {
        val points = BalanceConsumptionAggregator.monthly(
            listOf(
                sample(100.0, "2026-12-20T08:00:00Z"),
                sample(90.0, "2026-12-31T08:00:00Z"),
                sample(80.0, "2027-01-05T08:00:00Z")
            ),
            utc
        )
        assertEquals(listOf("2026-12", "2027-01"), points.map { it.periodKey })
        assertEquals(listOf(10.0, 10.0), points.map { it.consumed })
    }
    @Test
    fun `currency of the output is always the one of the latest sample`() {
        val points = BalanceConsumptionAggregator.daily(
            listOf(
                sample(100.0, "2026-11-14T08:00:00Z", "USD"),
                sample(95.0, "2026-11-15T08:00:00Z", "USD"),
                sample(90.0, "2026-11-16T08:00:00Z", "EUR")
            ),
            utc
        )
        assertTrue(points.all { it.currency == "EUR" })
    }
    @Test
    fun `a currency change between two samples is not counted as consumption`() {
        val points = BalanceConsumptionAggregator.daily(
            listOf(sample(100.0, "2026-11-14T08:00:00Z", "USD"), sample(90.0, "2026-11-15T08:00:00Z", "EUR")),
            utc
        )
        assertTrue(points.isEmpty() || points.single().consumed == 0.0)
    }
    @Test
    fun `the same samples fall in different days depending on the zone`() {
        val samples = listOf(sample(100.0, "2026-11-14T20:00:00Z"), sample(90.0, "2026-11-14T23:30:00Z"))
        assertEquals("2026-11-14", BalanceConsumptionAggregator.daily(samples, utc).single().periodKey)
        assertEquals("2026-11-15", BalanceConsumptionAggregator.daily(samples, paris).single().periodKey)
        assertEquals("2026-11-15", BalanceConsumptionAggregator.daily(samples, ZoneId.of("Asia/Tokyo")).single().periodKey)
        assertEquals("2026-11-14", BalanceConsumptionAggregator.daily(samples, ZoneId.of("America/Los_Angeles")).single().periodKey)
    }
    @Test
    fun `month boundary follows the local zone`() {
        val samples = listOf(sample(100.0, "2026-10-31T10:00:00Z"), sample(90.0, "2026-10-31T23:30:00Z"))
        assertEquals("2026-10", BalanceConsumptionAggregator.monthly(samples, utc).single().periodKey)
        assertEquals("2026-11", BalanceConsumptionAggregator.monthly(samples, paris).single().periodKey)
    }
    @Test
    fun `spring forward day keeps all samples in a single local day`() {
        val points = BalanceConsumptionAggregator.daily(
            listOf(
                sample(100.0, "2026-03-28T23:30:00Z"),
                sample(90.0, "2026-03-29T00:30:00Z"),
                sample(80.0, "2026-03-29T01:30:00Z"),
                sample(70.0, "2026-03-29T21:30:00Z")
            ),
            paris
        )
        assertEquals(listOf("2026-03-29"), points.map { it.periodKey })
        assertEquals(30.0, points.single().consumed, 1e-9)
    }
    @Test
    fun `fall back day keeps both occurrences of the repeated hour in the same local day`() {
        val points = BalanceConsumptionAggregator.daily(
            listOf(
                sample(100.0, "2026-10-25T00:30:00Z"),
                sample(95.0, "2026-10-25T01:30:00Z"),
                sample(90.0, "2026-10-25T22:30:00Z")
            ),
            paris
        )
        assertEquals(listOf("2026-10-25"), points.map { it.periodKey })
        assertEquals(10.0, points.single().consumed, 1e-9)
    }
    @Test
    fun `a day-long interval across a dst change is attributed to the later local day`() {
        val points = BalanceConsumptionAggregator.daily(
            listOf(sample(100.0, "2026-03-28T10:00:00Z"), sample(88.0, "2026-03-29T10:00:00Z")),
            paris
        )
        assertEquals(listOf("2026-03-29"), points.map { it.periodKey })
        assertEquals(12.0, points.single().consumed, 1e-9)
    }
    @Test
    fun `floating point sums stay close to the exact value`() {
        val samples = (0..10).map { i ->
            sample(1.0 - i * 0.1, "2026-11-15T${"%02d".format(i)}:00:00Z")
        }
        val points = BalanceConsumptionAggregator.daily(samples, utc)
        assertEquals(1.0, points.single().consumed, 1e-9)
    }
    @Test
    fun `input list is not mutated`() {
        val input = listOf(sample(80.0, "2026-11-16T08:00:00Z"), sample(100.0, "2026-11-14T08:00:00Z"))
        val copy = input.toList()
        BalanceConsumptionAggregator.daily(input, utc)
        assertEquals(copy, input)
    }
    @Test
    fun `daily and monthly agree on total consumption`() {
        val samples = listOf(
            sample(100.0, "2026-11-01T08:00:00Z"),
            sample(90.0, "2026-11-10T08:00:00Z"),
            sample(120.0, "2026-11-12T08:00:00Z"),
            sample(75.0, "2026-11-28T08:00:00Z")
        )
        val daily = BalanceConsumptionAggregator.daily(samples, utc).sumOf { it.consumed }
        val monthly = BalanceConsumptionAggregator.monthly(samples, utc).sumOf { it.consumed }
        assertEquals(55.0, daily, 1e-9)
        assertEquals(daily, monthly, 1e-9)
    }
}
