package fr.gildasledrogoff.tokenusage.core.domain.model

import fr.gildasledrogoff.tokenusage.core.alert.TimedValue
import java.time.Duration
import java.time.Instant

data class BalancePace(
    val dailyRate: Double,
    val recentDailyRate: Double?,
    val timeToDepletion: Duration?
) {
    val isAccelerating: Boolean
        get() = recentDailyRate != null && recentDailyRate > dailyRate * ACCELERATION_FACTOR

    companion object {
        val LOOKBACK: Duration = Duration.ofDays(7)
        private val RECENT: Duration = Duration.ofHours(24)
        private val MIN_SPAN: Duration = Duration.ofHours(1)
        private val MAX_ETA: Duration = Duration.ofDays(365)
        private const val ACCELERATION_FACTOR = 1.25
        private const val DAY_SECONDS = 86_400.0

        fun compute(samples: List<TimedValue>, now: Instant): BalancePace? {
            val points = samples
                .filter { !it.at.isAfter(now) && !it.at.isBefore(now.minus(LOOKBACK)) }
                .sortedBy { it.at }
            val rate = rate(points) ?: return null
            if (rate <= 0.0) return null
            val last = points.last().value
            if (last <= 0.0) return null
            val eta = Duration.ofSeconds((last / rate * DAY_SECONDS).toLong())
            val recent = rate(points.filter { !it.at.isBefore(now.minus(RECENT)) })
            return BalancePace(rate, recent, eta.takeIf { it <= MAX_ETA })
        }

        private fun rate(points: List<TimedValue>): Double? {
            if (points.size < 2) return null
            val span = Duration.between(points.first().at, points.last().at)
            if (span < MIN_SPAN) return null
            val spent = points.zipWithNext().sumOf { (a, b) -> (a.value - b.value).coerceAtLeast(0.0) }
            return spent / (span.seconds / DAY_SECONDS)
        }
    }
}
