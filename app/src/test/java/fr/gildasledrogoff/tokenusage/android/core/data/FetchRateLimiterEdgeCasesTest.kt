package fr.gildasledrogoff.tokenusage.core.data
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class FetchRateLimiterEdgeCasesTest {
    private val start: Instant = Instant.parse("2026-11-21T10:00:00Z")
    
    private fun TestScope.virtualClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = start.plusMillis(currentTime)
    }
    @Test
    fun `window boundary is exclusive at 15s and inclusive just before`() = runTest {
        val limiter = FetchRateLimiter(virtualClock())
        val last = start
        assertTrue(limiter.isCoolingDown(last))
        advanceBy(14_999)
        assertTrue(limiter.isCoolingDown(last))
        advanceBy(1)
        assertFalse(limiter.isCoolingDown(last))
        assertTrue(limiter.canFetchNow(last))
    }
    @Test
    fun `a new fetch restarts the window`() = runTest {
        val limiter = FetchRateLimiter(virtualClock())
        var last: Instant? = null
        assertTrue(limiter.canFetchNow(last))
        last = start.plusMillis(currentTime)
        advanceBy(15_000)
        assertTrue(limiter.canFetchNow(last))
        last = start.plusMillis(currentTime)
        advanceBy(5_000)
        assertFalse(limiter.canFetchNow(last))
        advanceBy(10_000)
        assertTrue(limiter.canFetchNow(last))
    }
    @Test
    fun `canFetchNow is always the negation of isCoolingDown`() = runTest {
        val limiter = FetchRateLimiter(virtualClock())
        for (offsetMs in listOf(-1L, 0L, 1L, 14_999L, 15_000L, 15_001L, 3_600_000L)) {
            val last = start.minusMillis(offsetMs)
            assertEquals("offset $offsetMs", !limiter.isCoolingDown(last), limiter.canFetchNow(last))
        }
    }
    @Test
    fun `very old timestamps allow fetching`() = runTest {
        val limiter = FetchRateLimiter(virtualClock())
        assertTrue(limiter.canFetchNow(Instant.EPOCH))
        assertTrue(limiter.canFetchNow(Instant.MIN.plusSeconds(0).coerceAtLeast(Instant.EPOCH)))
    }
    @Test
    fun `many concurrent callers get the same verdict for the same timestamp`() = runTest(StandardTestDispatcher()) {
        val limiter = FetchRateLimiter(virtualClock())
        val coolingLast = start
        val expiredLast = start.minus(Duration.ofMinutes(5))
        val verdicts = (1..200).map { i ->
            async {
                if (i % 2 == 0) limiter.canFetchNow(coolingLast) else limiter.canFetchNow(expiredLast)
            }
        }.awaitAll()
        assertEquals(100, verdicts.count { !it })
        assertEquals(100, verdicts.count { it })
    }
    @Test
    fun `concurrent callers observe the window expiring while they are suspended`() = runTest {
        val limiter = FetchRateLimiter(virtualClock())
        val last = start
        val before = async { limiter.canFetchNow(last) }
        val after = async {
            kotlinx.coroutines.delay(FetchRateLimiter.MIN_FETCH_INTERVAL.toMillis())
            limiter.canFetchNow(last)
        }
        assertFalse(before.await())
        assertTrue(after.await())
    }
    @Test
    fun `minimum interval constant is 15 seconds`() {
        assertEquals(Duration.ofSeconds(15), FetchRateLimiter.MIN_FETCH_INTERVAL)
    }
    @Test
    fun `a last fetch in the far future does not block fetching`() = runTest {
        val limiter = FetchRateLimiter(virtualClock())
        val future = start.plus(Duration.ofDays(1))
        assertTrue(limiter.canFetchNow(future))
    }
    @Test
    fun `current behaviour - a slightly future last fetch counts as cooling down`() = runTest {
        val limiter = FetchRateLimiter(virtualClock())
        assertTrue(limiter.isCoolingDown(start.plusSeconds(1)))
    }
    private fun TestScope.advanceBy(ms: Long) = testScheduler.advanceTimeBy(ms).also { testScheduler.runCurrent() }
    private fun Instant.coerceAtLeast(min: Instant): Instant = if (this < min) min else this
}
