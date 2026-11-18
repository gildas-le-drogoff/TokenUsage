package fr.gildasledrogoff.tokenusage.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class FetchRateLimiterTest {

    private var now: Instant = Instant.ofEpochMilli(1_000_000L)

    private val clock = object : Clock() {
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private val limiter = FetchRateLimiter(clock)

    @Test
    fun `never fetched allows fetch`() {
        assertTrue(limiter.canFetchNow(null))
        assertFalse(limiter.isCoolingDown(null))
    }

    @Test
    fun `within 15 seconds refuses to fetch`() {
        val lastFetchAt = now.minus(Duration.ofSeconds(5))
        assertFalse(limiter.canFetchNow(lastFetchAt))
        assertTrue(limiter.isCoolingDown(lastFetchAt))
    }

    @Test
    fun `exactly 15 seconds allows fetch`() {
        val lastFetchAt = now.minus(FetchRateLimiter.MIN_FETCH_INTERVAL)
        assertTrue(limiter.canFetchNow(lastFetchAt))
        assertFalse(limiter.isCoolingDown(lastFetchAt))
    }

    @Test
    fun `after cooldown allows fetch`() {
        val lastFetchAt = now.minus(Duration.ofSeconds(16))
        assertTrue(limiter.canFetchNow(lastFetchAt))
        assertFalse(limiter.isCoolingDown(lastFetchAt))
    }

    @Test
    fun `cooldown tracks moving clock`() {
        val lastFetchAt = now
        assertTrue(limiter.isCoolingDown(lastFetchAt))
        now = now.plus(Duration.ofSeconds(14))
        assertTrue(limiter.isCoolingDown(lastFetchAt))
        now = now.plus(Duration.ofSeconds(1))
        assertFalse(limiter.isCoolingDown(lastFetchAt))
    }
}
