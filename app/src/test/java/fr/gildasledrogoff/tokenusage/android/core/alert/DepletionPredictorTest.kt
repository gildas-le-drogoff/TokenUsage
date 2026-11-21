package fr.gildasledrogoff.tokenusage.core.alert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
class DepletionPredictorTest {
    private val clock = TestClock()
    private val now: Instant get() = clock.now
    private val predictor = DepletionPredictor(clock)
    private fun at(hoursAgo: Long, value: Double) = TimedValue(value, now.minus(Duration.ofHours(hoursAgo)))
    private fun hoursUntil(forecast: DepletionForecast) =
        Duration.between(now, forecast.depletionAt).toMillis() / 3_600_000.0
    @Test
    fun `balance - steady consumption gives the date the balance reaches zero`() {
        val forecast = predictor.predictBalance(listOf(at(48, 100.0), at(24, 90.0), at(0, 80.0)))
        assertNotNull(forecast)
        assertEquals(10.0 / 24, forecast!!.ratePerHour, 1e-9)
        assertEquals(192.0, hoursUntil(forecast), 1e-3)
    }
    @Test
    fun `balance - target other than zero is honoured`() {
        val forecast = predictor.predictBalance(
            listOf(at(48, 100.0), at(24, 90.0), at(0, 80.0)),
            target = 60.0
        )
        assertEquals(48.0, hoursUntil(forecast!!), 1e-3)
    }
    @Test
    fun `balance - points are used whatever their order`() {
        val forecast = predictor.predictBalance(listOf(at(0, 80.0), at(48, 100.0), at(24, 90.0)))
        assertEquals(192.0, hoursUntil(forecast!!), 1e-3)
    }
    @Test
    fun `balance - a top-up cuts the series instead of counting as negative consumption`() {
        val forecast = predictor.predictBalance(
            listOf(at(72, 50.0), at(48, 10.0), at(47, 200.0), at(24, 190.0), at(0, 180.0))
        )
        assertEquals(423.06, hoursUntil(forecast!!), 0.1)
    }
    @Test
    fun `balance - a top-up as last point leaves too little data`() {
        val forecast = predictor.predictBalance(listOf(at(48, 10.0), at(24, 5.0), at(0, 100.0)))
        assertNull(forecast)
    }
    @Test
    fun `balance - lookback ignores older measurements`() {
        val forecast = predictor.predictBalance(
            listOf(at(24 * 20, 1000.0), at(24 * 19, 100.0), at(48, 100.0), at(24, 99.0), at(0, 98.0))
        )
        assertEquals(98.0 * 24, hoursUntil(forecast!!), 1e-2)
    }
    @Test
    fun `balance - custom lookback`() {
        val points = listOf(at(48, 100.0), at(24, 90.0), at(0, 80.0))
        assertNull(predictor.predictBalance(points, lookback = Duration.ofHours(10)))
        assertNotNull(predictor.predictBalance(points, lookback = Duration.ofHours(50)))
    }
    @Test
    fun `balance - no prediction without enough data`() {
        assertNull(predictor.predictBalance(emptyList()))
        assertNull(predictor.predictBalance(listOf(at(0, 10.0))))
        val close = listOf(TimedValue(10.0, now.minusSeconds(300)), TimedValue(9.0, now))
        assertNull(predictor.predictBalance(close))
    }
    @Test
    fun `balance - no prediction when nothing is consumed`() {
        assertNull(predictor.predictBalance(listOf(at(48, 50.0), at(24, 50.0), at(0, 50.0))))
    }
    @Test
    fun `balance - no prediction when already empty or under the target`() {
        assertNull(predictor.predictBalance(listOf(at(24, 5.0), at(0, 0.0))))
        assertNull(predictor.predictBalance(listOf(at(24, 100.0), at(0, 50.0)), target = 60.0))
    }
    @Test
    fun `balance - absurdly distant depletion is not a prediction`() {
        val forecast = predictor.predictBalance(listOf(at(48, 1_000_000.0), at(0, 999_999.0)))
        assertNull(forecast)
    }
    @Test
    fun `balance - future measurements are ignored`() {
        val future = TimedValue(1.0, now.plusSeconds(3600))
        val forecast = predictor.predictBalance(listOf(at(48, 100.0), at(24, 90.0), at(0, 80.0), future))
        assertEquals(192.0, hoursUntil(forecast!!), 1e-3)
    }
    @Test
    fun `balance - regression smooths noisy measurements`() {
        val forecast = predictor.predictBalance(
            listOf(at(40, 100.0), at(30, 96.0), at(20, 95.0), at(10, 90.0), at(0, 88.0))
        )
        assertNotNull(forecast)
        assertTrue(forecast!!.ratePerHour > 0.0)
    }
    @Test
    fun `quota - extrapolates the usage speed up to 100 percent`() {
        val forecast = predictor.predictQuota(listOf(at(2, 0.2), at(1, 0.4), at(0, 0.6)))
        assertEquals(0.2, forecast!!.ratePerHour, 1e-9)
        assertEquals(2.0, hoursUntil(forecast), 1e-3)
    }
    @Test
    fun `quota - exhausted before the reset or not`() {
        val forecast = predictor.predictQuota(listOf(at(2, 0.2), at(1, 0.4), at(0, 0.6)))!!
        assertTrue(forecast.isBefore(now.plus(Duration.ofHours(3))))
        assertFalse(forecast.isBefore(now.plus(Duration.ofHours(1))))
        assertTrue(forecast.isBefore(null))
    }
    @Test
    fun `quota - a usage drop is a reset and restarts the series`() {
        val forecast = predictor.predictQuota(listOf(at(3, 0.9), at(2, 0.1), at(1, 0.2), at(0, 0.3)))
        assertEquals(7.0, hoursUntil(forecast!!), 1e-3)
    }
    @Test
    fun `quota - no prediction when usage is flat, full or too little data`() {
        assertNull(predictor.predictQuota(listOf(at(2, 0.3), at(1, 0.3), at(0, 0.3))))
        assertNull(predictor.predictQuota(listOf(at(2, 0.9), at(1, 0.95), at(0, 1.0))))
        assertNull(predictor.predictQuota(listOf(at(0, 0.3))))
        assertNull(predictor.predictQuota(emptyList()))
    }
    @Test
    fun `forecast within a horizon`() {
        val forecast = predictor.predictQuota(listOf(at(2, 0.2), at(1, 0.4), at(0, 0.6)))!!
        assertTrue(forecast.isWithin(Duration.ofHours(3), now))
        assertFalse(forecast.isWithin(Duration.ofHours(1), now))
    }
}
