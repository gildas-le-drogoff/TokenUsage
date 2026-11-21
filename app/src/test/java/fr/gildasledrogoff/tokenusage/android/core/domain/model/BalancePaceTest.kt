package fr.gildasledrogoff.tokenusage.core.domain.model

import fr.gildasledrogoff.tokenusage.core.alert.TimedValue
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BalancePaceTest {
    private val now = Instant.parse("2026-11-21T12:00:00Z")
    private fun at(daysAgo: Double, v: Double) =
        TimedValue(v, now.minusSeconds((daysAgo * 86400).toLong()))

    @Test
    fun `rythme moyen et epuisement`() {
        val pace = BalancePace.compute(listOf(at(4.0, 10.0), at(2.0, 8.0), at(0.0, 6.0)), now)!!
        assertEquals(1.0, pace.dailyRate, 1e-6)
        assertEquals(Duration.ofDays(6), pace.timeToDepletion)
    }

    @Test
    fun `les recharges sont ignorees`() {
        val pace = BalancePace.compute(listOf(at(4.0, 5.0), at(2.0, 3.0), at(1.0, 23.0), at(0.0, 22.0)), now)!!
        assertEquals(3.0 / 4.0, pace.dailyRate, 1e-6)
    }

    @Test
    fun `historique insuffisant ou sans consommation`() {
        assertNull(BalancePace.compute(listOf(at(0.0, 5.0)), now))
        assertNull(BalancePace.compute(listOf(at(3.0, 5.0), at(0.0, 5.0)), now))
    }

    @Test
    fun `detecte l acceleration`() {
        val pace = BalancePace.compute(listOf(at(6.0, 20.0), at(1.0, 19.0), at(0.0, 16.0)), now)!!
        assertTrue(pace.isAccelerating)
    }
}
