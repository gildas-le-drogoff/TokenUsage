package fr.gildasledrogoff.tokenusage.core.domain.model

import fr.gildasledrogoff.tokenusage.core.alert.TimedValue
import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.floor

object HistoricalUsagePace {
    const val GRID_POINT_COUNT = 169
    const val MAX_CYCLES = 8
    const val MIN_COMPLETE_CYCLES = 3
    private const val MIN_CYCLE_SAMPLES = 6
    private const val RECENCY_TAU_CYCLES = 3.0
    private const val RESET_DROP_TOLERANCE = 5.0
    private const val EPSILON = 1e-9
    private val BOUNDARY_COVERAGE: Duration = Duration.ofHours(24)
    private val BOUNDARY_MARGIN: Duration = Duration.ofMinutes(15)

    fun lookbackStart(resetsAt: Instant, windowMinutes: Int): Instant =
        resetsAt.minus(Duration.ofMinutes(windowMinutes.toLong() * (MAX_CYCLES + 1)))

    /**
     * @param utilization part consommée de la fenêtre courante, entre 0.0 et 1.0
     * @param samples relevés d'utilisation en pourcentage (0–100)
     * @return null si l'historique compte moins de [MIN_COMPLETE_CYCLES] cycles complets
     */
    fun compute(
        utilization: Double,
        resetsAt: Instant?,
        windowMinutes: Int?,
        samples: List<TimedValue>,
        now: Instant = Instant.now()
    ): UsagePace? {
        if (resetsAt == null || windowMinutes == null || windowMinutes <= 0) return null
        val duration = windowMinutes * 60.0
        val untilReset = Duration.between(now, resetsAt).toMillis() / 1000.0
        if (untilReset <= 0 || untilReset > duration) return null
        val elapsed = (duration - untilReset).coerceIn(0.0, duration)
        val actual = (utilization * 100).coerceIn(0.0, 100.0)
        if (elapsed == 0.0 && actual > 0) return null
        val uNow = (elapsed / duration).coerceIn(0.0, 1.0)

        val cycles = pastCycles(samples, resetsAt, windowMinutes)
        if (cycles.size < MIN_COMPLETE_CYCLES) return null
        val weights = cycles.map { exp(-it.age / RECENCY_TAU_CYCLES) }
        val totalWeight = weights.sum()
        if (totalWeight <= EPSILON) return null
        val totalWeightSquared = weights.sumOf { it * it }
        val nEff = if (totalWeightSquared > EPSILON) totalWeight * totalWeight / totalWeightSquared else 0.0
        val lambda = ((nEff - 2) / 6).coerceIn(0.0, 1.0)

        val denominator = (GRID_POINT_COUNT - 1).toDouble()
        val expectedCurve = DoubleArray(GRID_POINT_COUNT) { index ->
            val u = index / denominator
            val median = weightedMedian(cycles.map { it.curve[index] }, weights)
            val linear = 100 * u
            (lambda * median + (1 - lambda) * linear).coerceIn(0.0, linear)
        }
        var runningExpected = 0.0
        for (index in expectedCurve.indices) {
            runningExpected = maxOf(runningExpected, expectedCurve[index])
            expectedCurve[index] = runningExpected
        }
        val expectedNow = interpolate(expectedCurve, uNow)

        var runOutMass = 0.0
        val crossings = mutableListOf<Pair<Double, Double>>()
        cycles.forEachIndexed { i, cycle ->
            val extended = extendPastCap(cycle.curve)
            val weight = weights[i]
            val shift = actual - interpolate(extended, uNow)
            if (extended.last() + shift >= 100 - EPSILON) {
                runOutMass += weight
                firstCrossing(uNow, extended, shift, actual)?.let { crossingU ->
                    crossings += maxOf(0.0, (crossingU - uNow) * duration) to weight
                }
            }
        }

        val probability = ((runOutMass + 0.5) / (totalWeight + 1)).coerceIn(0.0, 1.0)
        var willLastToReset = probability < 0.5
        var etaSeconds: Double? = null
        if (actual >= 100) {
            willLastToReset = false
            etaSeconds = 0.0
        } else if (!willLastToReset) {
            if (crossings.isEmpty()) {
                willLastToReset = true
            } else {
                etaSeconds = maxOf(0.0, weightedMedian(crossings.map { it.first }, crossings.map { it.second }))
            }
        }
        return UsagePace.historical(
            expectedUsedPercent = expectedNow,
            actualUsedPercent = actual,
            etaToExhaustion = etaSeconds?.let { Duration.ofMillis((it * 1000).toLong()) },
            willLastToReset = willLastToReset
        )
    }

    private class Cycle(val age: Int, val curve: DoubleArray)

    private fun pastCycles(samples: List<TimedValue>, resetsAt: Instant, windowMinutes: Int): List<Cycle> {
        val window = Duration.ofMinutes(windowMinutes.toLong())
        val coverage = minOf(BOUNDARY_COVERAGE, window.dividedBy(7))
        return (1..MAX_CYCLES).mapNotNull { age ->
            val end = resetsAt.minus(window.multipliedBy(age.toLong()))
            val start = end.minus(window)
            val inCycle = samples
                .filter { it.at.isAfter(start.plus(BOUNDARY_MARGIN)) && it.at.isBefore(end.minus(BOUNDARY_MARGIN)) }
                .sortedBy { it.at }
            if (!isComplete(inCycle, start, end, coverage) || hasResetInside(inCycle)) return@mapNotNull null
            reconstructCurve(inCycle, start, window)?.let { Cycle(age, it) }
        }
    }

    private fun isComplete(samples: List<TimedValue>, start: Instant, end: Instant, coverage: Duration): Boolean {
        if (samples.size < MIN_CYCLE_SAMPLES) return false
        val startBoundary = start.plus(coverage)
        val endBoundary = end.minus(coverage)
        return samples.any { !it.at.isAfter(startBoundary) } && samples.any { !it.at.isBefore(endBoundary) }
    }

    private fun hasResetInside(samples: List<TimedValue>): Boolean {
        var runningMax = 0.0
        for (sample in samples) {
            if (sample.value < runningMax - RESET_DROP_TOLERANCE) return true
            runningMax = maxOf(runningMax, sample.value)
        }
        return false
    }

    private fun reconstructCurve(samples: List<TimedValue>, start: Instant, window: Duration): DoubleArray? {
        if (samples.isEmpty()) return null
        val durationMillis = window.toMillis().toDouble()
        val observed = samples.map { sample ->
            val u = (Duration.between(start, sample.at).toMillis() / durationMillis).coerceIn(0.0, 1.0)
            u to sample.value.coerceIn(0.0, 100.0)
        }
        val endValue = monotone(observed.sortedWith(POINT_ORDER)).last().second
        val points = monotone((observed + (0.0 to 0.0) + (1.0 to endValue)).sortedWith(POINT_ORDER))

        val denominator = (GRID_POINT_COUNT - 1).toDouble()
        val first = points.first()
        val last = points.last()
        var upper = 1
        val curve = DoubleArray(GRID_POINT_COUNT) { index ->
            val u = index / denominator
            when {
                u <= first.first -> first.second
                u >= last.first -> last.second
                else -> {
                    while (upper < points.size && points[upper].first < u) upper++
                    val hi = points[minOf(upper, points.size - 1)]
                    val lo = points[maxOf(0, upper - 1)]
                    if (hi.first <= lo.first) maxOf(lo.second, hi.second)
                    else {
                        val ratio = ((u - lo.first) / (hi.first - lo.first)).coerceIn(0.0, 1.0)
                        lo.second + (hi.second - lo.second) * ratio
                    }
                }
            }
        }
        var curveMax = 0.0
        for (index in curve.indices) {
            curveMax = maxOf(curveMax, curve[index].coerceIn(0.0, 100.0))
            curve[index] = curveMax
        }
        return curve
    }

    private val POINT_ORDER = compareBy<Pair<Double, Double>>({ it.first }, { it.second })

    private fun monotone(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        var runningMax = 0.0
        return points.map { (u, value) ->
            runningMax = maxOf(runningMax, value)
            u to runningMax
        }
    }

    private fun extendPastCap(curve: DoubleArray): DoubleArray {
        val extended = curve.copyOf()
        val capIndex = extended.indexOfFirst { it >= 100 - EPSILON }
        if (capIndex > 0 && capIndex < extended.size - 1) {
            val denominator = (GRID_POINT_COUNT - 1).toDouble()
            val slope = extended[capIndex] / (capIndex / denominator)
            for (i in capIndex until extended.size) extended[i] = slope * (i / denominator)
        }
        return extended
    }

    private fun firstCrossing(uNow: Double, curve: DoubleArray, shift: Double, actualAtNow: Double): Double? {
        if (curve.size < 2) return null
        val denominator = (curve.size - 1).toDouble()
        var previousU = uNow
        var previousValue = actualAtNow
        val startIndex = minOf(curve.size - 1, maxOf(1, floor(uNow * denominator).toInt() + 1))
        for (index in startIndex until curve.size) {
            val u = index / denominator
            if (u <= uNow + EPSILON) continue
            val value = (curve[index] + shift).coerceIn(0.0, 100.0)
            if (previousValue < 100 - EPSILON && value >= 100 - EPSILON) {
                val delta = value - previousValue
                if (kotlin.math.abs(delta) <= EPSILON) return u
                val ratio = ((100 - previousValue) / delta).coerceIn(0.0, 1.0)
                return (previousU + ratio * (u - previousU)).coerceIn(uNow, 1.0)
            }
            previousU = u
            previousValue = value
        }
        return null
    }

    private fun interpolate(curve: DoubleArray, u: Double): Double {
        if (curve.isEmpty()) return 0.0
        if (curve.size == 1) return curve[0]
        val scaled = u.coerceIn(0.0, 1.0) * (curve.size - 1)
        val lower = floor(scaled).toInt()
        val upper = minOf(curve.size - 1, lower + 1)
        if (lower == upper) return curve[lower]
        return curve[lower] + (curve[upper] - curve[lower]) * (scaled - lower)
    }

    private fun weightedMedian(values: List<Double>, weights: List<Double>): Double {
        if (values.size != weights.size || values.isEmpty()) return 0.0
        val pairs = values.zip(weights.map { maxOf(0.0, it) }).sortedBy { it.first }
        val total = pairs.sumOf { it.second }
        if (total <= EPSILON) return values.sorted()[values.size / 2]
        val threshold = total / 2
        var cumulative = 0.0
        for ((value, weight) in pairs) {
            cumulative += weight
            if (cumulative >= threshold) return value
        }
        return pairs.last().first
    }
}
