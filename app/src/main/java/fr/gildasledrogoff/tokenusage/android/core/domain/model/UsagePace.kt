package fr.gildasledrogoff.tokenusage.core.domain.model

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

data class UsagePace(
    val stage: Stage,
    val deltaPercent: Double,
    val expectedUsedPercent: Double,
    val actualUsedPercent: Double,
    val etaToExhaustion: Duration?,
    val willLastToReset: Boolean
) {
    enum class Stage { ON_TRACK, SLIGHTLY_AHEAD, AHEAD, FAR_AHEAD, SLIGHTLY_BEHIND, BEHIND, FAR_BEHIND }

    val isInDeficit: Boolean get() = stage in DEFICIT_STAGES

    companion object {
        private val DEFICIT_STAGES = setOf(Stage.SLIGHTLY_AHEAD, Stage.AHEAD, Stage.FAR_AHEAD)

        /**
         * @param utilization part consommée entre 0.0 et 1.0
         * @return null si la fenêtre ou la réinitialisation est inconnue ou incohérente
         */
        fun compute(
            utilization: Double,
            resetsAt: Instant?,
            windowMinutes: Int?,
            now: Instant = Instant.now()
        ): UsagePace? {
            if (resetsAt == null || windowMinutes == null || windowMinutes <= 0) return null
            val duration = windowMinutes * 60.0
            val untilReset = Duration.between(now, resetsAt).toMillis() / 1000.0
            if (untilReset <= 0 || untilReset > duration) return null
            val elapsed = (duration - untilReset).coerceIn(0.0, duration)
            val expected = (elapsed / duration * 100).coerceIn(0.0, 100.0)
            val actual = (utilization * 100).coerceIn(0.0, 100.0)
            if (elapsed == 0.0 && actual > 0) return null
            val delta = actual - expected

            var eta: Duration? = null
            var lasts = false
            when {
                actual >= 100 -> eta = Duration.ZERO
                elapsed > 0 && actual > 0 -> {
                    val candidate = (100 - actual) / (actual / elapsed)
                    if (candidate >= untilReset) lasts = true
                    else eta = Duration.ofMillis((candidate * 1000).toLong())
                }
                elapsed > 0 -> lasts = true
            }
            return UsagePace(stageFor(delta), delta, expected, actual, eta, lasts)
        }

        internal fun historical(
            expectedUsedPercent: Double,
            actualUsedPercent: Double,
            etaToExhaustion: Duration?,
            willLastToReset: Boolean
        ): UsagePace {
            val expected = expectedUsedPercent.coerceIn(0.0, 100.0)
            val actual = actualUsedPercent.coerceIn(0.0, 100.0)
            val delta = actual - expected
            return UsagePace(stageFor(delta), delta, expected, actual, etaToExhaustion, willLastToReset)
        }

        fun monthlyWindowMinutes(resetsAt: Instant, months: Int = 1): Int? {
            if (months <= 0) return null
            val start = resetsAt.atOffset(ZoneOffset.UTC).minusMonths(months.toLong()).toInstant()
            val minutes = Duration.between(start, resetsAt).toMinutes()
            return minutes.takeIf { it > 0 }?.toInt()
        }

        private fun stageFor(delta: Double): Stage {
            val a = abs(delta)
            return when {
                a <= 2 -> Stage.ON_TRACK
                a <= 6 -> if (delta >= 0) Stage.SLIGHTLY_AHEAD else Stage.SLIGHTLY_BEHIND
                a <= 12 -> if (delta >= 0) Stage.AHEAD else Stage.BEHIND
                else -> if (delta >= 0) Stage.FAR_AHEAD else Stage.FAR_BEHIND
            }
        }
    }
}
