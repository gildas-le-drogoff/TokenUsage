package fr.gildasledrogoff.tokenusage.core.domain.model

import fr.gildasledrogoff.tokenusage.core.alert.TimedValue
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalUsagePaceTest {
    private val now = Instant.parse("2026-11-21T12:00:00Z")
    private val week = Duration.ofDays(7)
    private val weekMinutes = 7 * 24 * 60
    private val resetsAt = now.plus(Duration.ofDays(3))

    private fun history(
        cycles: Int,
        peak: Double,
        shape: (Double) -> Double = { u -> u * u * u },
        until: Instant = now
    ): List<TimedValue> {
        val samples = mutableListOf<TimedValue>()
        for (age in cycles downTo 1) {
            val start = resetsAt.minus(week.multipliedBy(age.toLong() + 1))
            var t = start
            while (t.isBefore(start.plus(week)) && !t.isAfter(until)) {
                val u = Duration.between(start, t).toMillis().toDouble() / week.toMillis()
                samples += TimedValue((peak * shape(u)).coerceAtMost(100.0), t)
                t = t.plus(Duration.ofHours(2))
            }
        }
        return samples
    }

    @Test
    fun `null sans assez de cycles complets`() {
        assertNull(HistoricalUsagePace.compute(0.3, resetsAt, weekMinutes, history(2, 90.0), now))
        assertNull(HistoricalUsagePace.compute(0.3, resetsAt, weekMinutes, emptyList(), now))
        assertNull(HistoricalUsagePace.compute(0.3, null, weekMinutes, history(5, 90.0), now))
    }

    @Test
    fun `l'usage attendu suit la courbe des cycles passes plutot que la droite`() {
        val samples = history(8, 90.0)
        val pace = HistoricalUsagePace.compute(0.30, resetsAt, weekMinutes, samples, now)!!
        val linear = UsagePace.compute(0.30, resetsAt, weekMinutes, now)!!
        assertTrue(linear.expectedUsedPercent > 55)
        assertTrue(pace.expectedUsedPercent < linear.expectedUsedPercent)
        assertTrue(pace.deltaPercent > linear.deltaPercent)
    }

    @Test
    fun `l'attendu ne depasse jamais le rythme lineaire`() {
        val samples = history(8, 140.0, shape = { u -> Math.sqrt(u) })
        val pace = HistoricalUsagePace.compute(0.50, resetsAt, weekMinutes, samples, now)!!
        val linear = UsagePace.compute(0.50, resetsAt, weekMinutes, now)!!
        assertTrue(pace.expectedUsedPercent <= linear.expectedUsedPercent + 1e-6)
    }

    @Test
    fun `projette l'epuisement en decalant les courbes passees`() {
        val pace = HistoricalUsagePace.compute(0.60, resetsAt, weekMinutes, history(8, 90.0), now)!!
        assertFalse(pace.willLastToReset)
        val eta = pace.etaToExhaustion
        assertNotNull(eta)
        eta!!
        assertTrue(eta > Duration.ZERO && eta < Duration.ofDays(3))
    }

    @Test
    fun `tient jusqu'a la reinitialisation quand l'usage reste dans la norme`() {
        val pace = HistoricalUsagePace.compute(0.10, resetsAt, weekMinutes, history(8, 50.0), now)!!
        assertTrue(pace.willLastToReset)
        assertNull(pace.etaToExhaustion)
    }

    @Test
    fun `quota epuise donne une echeance nulle`() {
        val pace = HistoricalUsagePace.compute(1.0, resetsAt, weekMinutes, history(8, 90.0), now)!!
        assertEquals(Duration.ZERO, pace.etaToExhaustion)
        assertFalse(pace.willLastToReset)
    }

    @Test
    fun `ecarte les cycles dont la reinitialisation est decalee`() {
        val shifted = history(8, 90.0).map { TimedValue(it.value, it.at.plus(Duration.ofDays(2))) }
            .filter { !it.at.isAfter(now) }
        assertNull(HistoricalUsagePace.compute(0.3, resetsAt, weekMinutes, shifted, now))
    }

    @Test
    fun `ecarte les cycles sans releve en debut ou fin de fenetre`() {
        val sparse = history(8, 90.0).filter {
            val offset = Duration.between(resetsAt.minus(week.multipliedBy(9)), it.at).toHours() % (7 * 24)
            offset in 30..130
        }
        assertNull(HistoricalUsagePace.compute(0.3, resetsAt, weekMinutes, sparse, now))
    }

    @Test
    fun `duree d'un cycle mensuel en mois civils UTC`() {
        assertEquals(31 * 24 * 60, UsagePace.monthlyWindowMinutes(Instant.parse("2026-04-01T00:00:00Z")))
        assertEquals(28 * 24 * 60, UsagePace.monthlyWindowMinutes(Instant.parse("2026-03-01T00:00:00Z")))
        assertEquals((31 + 28) * 24 * 60, UsagePace.monthlyWindowMinutes(Instant.parse("2026-03-01T00:00:00Z"), 2))
        assertNull(UsagePace.monthlyWindowMinutes(Instant.parse("2026-03-01T00:00:00Z"), 0))
    }

    @Test
    fun `une fenetre mensuelle a desormais une allure lineaire`() {
        val reset = now.plus(Duration.ofDays(12))
        val minutes = UsagePace.monthlyWindowMinutes(reset)
        assertNotNull(UsagePace.compute(0.27, reset, minutes, now))
    }
}
