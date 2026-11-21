package fr.gildasledrogoff.tokenusage.core.alert
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

data class TimedValue(val value: Double, val at: Instant)

data class DepletionForecast(
    val depletionAt: Instant,
    
    val ratePerHour: Double
) {
    fun isWithin(horizon: Duration, now: Instant): Boolean = !depletionAt.isAfter(now.plus(horizon))
    
    fun isBefore(resetsAt: Instant?): Boolean = resetsAt == null || depletionAt.isBefore(resetsAt)
}

class DepletionPredictor @Inject constructor(private val clock: Clock) {
    
    fun predictBalance(
        points: List<TimedValue>,
        target: Double = 0.0,
        lookback: Duration = DEFAULT_LOOKBACK
    ): DepletionForecast? {
        val segment = segment(points, lookback) { previous, current ->
            current.value > previous.value + TOP_UP_EPSILON
        }
        val slope = slopePerHour(segment) ?: return null
        val last = segment.last()
        if (slope >= -MIN_RATE || last.value <= target) return null
        return forecast(last, remaining = last.value - target, rate = -slope)
    }
    
    fun predictQuota(
        samples: List<TimedValue>,
        lookback: Duration = DEFAULT_LOOKBACK
    ): DepletionForecast? {
        val segment = segment(samples, lookback) { previous, current ->
            current.value < previous.value - RESET_EPSILON
        }
        val slope = slopePerHour(segment) ?: return null
        val last = segment.last()
        if (slope <= MIN_RATE || last.value >= 1.0) return null
        return forecast(last, remaining = 1.0 - last.value, rate = slope)
    }
    private fun forecast(last: TimedValue, remaining: Double, rate: Double): DepletionForecast? {
        val hours = remaining / rate
        if (hours.isNaN() || hours > MAX_HOURS) return null
        return DepletionForecast(
            depletionAt = last.at.plusMillis((hours * MILLIS_PER_HOUR).toLong()),
            ratePerHour = rate
        )
    }
    
    private fun segment(
        points: List<TimedValue>,
        lookback: Duration,
        isBreak: (previous: TimedValue, current: TimedValue) -> Boolean
    ): List<TimedValue> {
        val now = clock.instant()
        val earliest = now.minus(lookback)
        val recent = points
            .filter { !it.at.isAfter(now) && !it.at.isBefore(earliest) }
            .sortedBy { it.at }
        var start = 0
        for (i in 1 until recent.size) {
            if (isBreak(recent[i - 1], recent[i])) start = i
        }
        return recent.subList(start, recent.size)
    }
    
    private fun slopePerHour(segment: List<TimedValue>): Double? {
        if (segment.size < 2) return null
        val first = segment.first().at
        if (Duration.between(first, segment.last().at) < MIN_SPAN) return null
        val xs = segment.map { Duration.between(first, it.at).toMillis() / MILLIS_PER_HOUR }
        val meanX = xs.average()
        val meanY = segment.map { it.value }.average()
        var covariance = 0.0
        var variance = 0.0
        for (i in segment.indices) {
            covariance += (xs[i] - meanX) * (segment[i].value - meanY)
            variance += (xs[i] - meanX) * (xs[i] - meanX)
        }
        if (variance <= 0.0) return null
        return covariance / variance
    }
    companion object {
        val DEFAULT_LOOKBACK: Duration = Duration.ofDays(7)
        
        val MIN_SPAN: Duration = Duration.ofMinutes(10)
        private const val MIN_RATE = 1e-9
        private const val TOP_UP_EPSILON = 1e-6
        private const val RESET_EPSILON = 1e-3
        private const val MILLIS_PER_HOUR = 3_600_000.0
        private const val MAX_HOURS = 365.0 * 24
    }
}
