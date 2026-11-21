package fr.gildasledrogoff.tokenusage.core.data

import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

class FetchRateLimiter @Inject constructor(
    private val clock: Clock
) {

    fun canFetchNow(lastFetchAt: Instant?): Boolean = !isCoolingDown(lastFetchAt)

    fun isCoolingDown(lastFetchAt: Instant?): Boolean {
        lastFetchAt ?: return false
        val elapsed = Duration.between(lastFetchAt, clock.instant())
        // A last fetch far in the future (clock moved back) must not block fetching until the clock catches up.
        return elapsed < MIN_FETCH_INTERVAL && elapsed > MIN_FETCH_INTERVAL.negated()
    }

    companion object {
        val MIN_FETCH_INTERVAL: Duration = Duration.ofSeconds(15)
    }
}
