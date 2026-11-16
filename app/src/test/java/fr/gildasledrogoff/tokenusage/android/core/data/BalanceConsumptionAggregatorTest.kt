package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.data.BalanceConsumptionAggregator.Sample
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class BalanceConsumptionAggregatorTest {

    private val zone = ZoneId.of("UTC")

    private fun sample(balance: Double, isoTimestamp: String) =
        Sample(totalBalance = balance, currency = "CNY", timestamp = Instant.parse(isoTimestamp))

    @Test
    fun `daily consumption sums positive balance drops per day`() {
        val points = BalanceConsumptionAggregator.daily(
            listOf(
                sample(100.0, "2026-11-14T22:00:00Z"),
                sample(90.0, "2026-11-15T08:00:00Z"),
                sample(85.0, "2026-11-15T20:00:00Z")
            ),
            zone
        )

        assertEquals(1, points.size)
        assertEquals("2026-11-15", points[0].periodKey)
        assertEquals(15.0, points[0].consumed, 0.0001)
        assertEquals("CNY", points[0].currency)
    }

    @Test
    fun `delta spanning midnight is attributed to the later day`() {
        val points = BalanceConsumptionAggregator.daily(
            listOf(
                sample(100.0, "2026-11-14T23:00:00Z"),
                sample(80.0, "2026-11-15T01:00:00Z")
            ),
            zone
        )

        assertEquals(1, points.size)
        assertEquals("2026-11-15", points.single().periodKey)
        assertEquals(20.0, points.single().consumed, 0.0001)
    }

    @Test
    fun `top ups are not counted as negative consumption`() {
        val points = BalanceConsumptionAggregator.daily(
            listOf(
                sample(100.0, "2026-11-15T08:00:00Z"),
                sample(95.0, "2026-11-15T10:00:00Z"),
                sample(150.0, "2026-11-15T12:00:00Z"),
                sample(140.0, "2026-11-15T14:00:00Z")
            ),
            zone
        )

        assertEquals(1, points.size)
        assertEquals(15.0, points.single().consumed, 0.0001)
    }

    @Test
    fun `monthly buckets merge days of the same month`() {
        val points = BalanceConsumptionAggregator.monthly(
            listOf(
                sample(100.0, "2026-10-31T12:00:00Z"),
                sample(90.0, "2026-11-05T12:00:00Z"),
                sample(80.0, "2026-11-20T12:00:00Z")
            ),
            zone
        )

        assertEquals(1, points.size)
        assertEquals("2026-11", points.single().periodKey)
        assertEquals(20.0, points.single().consumed, 0.0001)
    }

    @Test
    fun `unsorted input samples are handled`() {
        val points = BalanceConsumptionAggregator.daily(
            listOf(
                sample(85.0, "2026-11-15T20:00:00Z"),
                sample(100.0, "2026-11-14T22:00:00Z"),
                sample(90.0, "2026-11-15T08:00:00Z")
            ),
            zone
        )

        assertEquals(1, points.size)
        assertEquals(15.0, points.single().consumed, 0.0001)
    }

    @Test
    fun `empty or single sample input produces no points`() {
        assertEquals(emptyList<Nothing>(), BalanceConsumptionAggregator.daily(emptyList(), zone))
        assertEquals(
            emptyList<Nothing>(),
            BalanceConsumptionAggregator.monthly(listOf(sample(50.0, "2026-11-15T08:00:00Z")), zone)
        )
    }

    @Test
    fun `currency is taken from the latest sample`() {
        val points = BalanceConsumptionAggregator.daily(
            listOf(
                Sample(100.0, "USD", Instant.parse("2026-11-14T22:00:00Z")),
                Sample(90.0, "CNY", Instant.parse("2026-11-15T08:00:00Z"))
            ),
            zone
        )

        assertEquals("CNY", points.single().currency)
    }
}
