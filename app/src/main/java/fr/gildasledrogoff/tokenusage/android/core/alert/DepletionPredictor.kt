package fr.gildasledrogoff.tokenusage.core.alert

import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** A value measured at a given time. */
data class TimedValue(val value: Double, val at: Instant)

/** Estimated exhaustion of a balance or of a quota window. */
data class DepletionForecast(
    val depletionAt: Instant,
    /** Consumption speed: balance units (or usage fraction) per hour. */
    val ratePerHour: Double
) {
    fun isWithin(horizon: Duration, now: Instant): Boolean = !depletionAt.isAfter(now.plus(horizon))

    /** `true` when the exhaustion happens before the window is reset (or no reset is known). */
    fun isBefore(resetsAt: Instant?): Boolean = resetsAt == null || depletionAt.isBefore(resetsAt)
}

/**
 * Linear extrapolation of the consumption speed. Pure logic, no Android.
 *
 * Both predictions return `null` ("no prediction") when the data are not
 * enough, when nothing is consumed, or when the result is absurdly far away.
 */
class DepletionPredictor @Inject constructor(private val clock: Clock) {

    /**
     * Instant at which a balance reaches [target], from its measurements [points]
     * (the last one is the current balance).
     *
     * Only the last [lookback] is used, and only the part after the last top-up:
     * a rising balance cuts the series instead of counting as negative consumption.
     */
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

    /**
     * Instant at which a quota window (usage fraction 0..1) reaches 100 %, from the
     * measurements [samples] of the current cycle (the last one is the current usage).
     * A drop of the usage is a reset: the series restarts there.
     */
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

    /** Recent points, in time order, restarting after the last "break" (top-up or reset). */
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

    /** Least-squares slope (value per hour), or `null` if the series is too short. */
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

        /** Shortest span of measurements that gives a usable speed. */
        val MIN_SPAN: Duration = Duration.ofMinutes(10)
        private const val MIN_RATE = 1e-9
        private const val TOP_UP_EPSILON = 1e-6
        private const val RESET_EPSILON = 1e-3
        private const val MILLIS_PER_HOUR = 3_600_000.0
        private const val MAX_HOURS = 365.0 * 24
    }
}
