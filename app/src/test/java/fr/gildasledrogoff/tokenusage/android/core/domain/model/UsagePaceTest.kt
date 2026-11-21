package fr.gildasledrogoff.tokenusage.core.domain.model

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsagePaceTest {
    private val now = Instant.parse("2026-11-21T12:00:00Z")

    @Test
    fun `retourne null sans fenetre ou sans reset`() {
        assertNull(UsagePace.compute(0.5, null, 300, now))
        assertNull(UsagePace.compute(0.5, now.plusSeconds(3600), null, now))
        assertNull(UsagePace.compute(0.5, now.minusSeconds(1), 300, now))
    }

    @Test
    fun `en deficit quand la consommation depasse le temps ecoule`() {
        val pace = UsagePace.compute(0.95, now.plus(Duration.ofHours(1)), 300, now)!!
        assertEquals(15.0, pace.deltaPercent, 0.01)
        assertEquals(UsagePace.Stage.FAR_AHEAD, pace.stage)
        assertTrue(pace.isInDeficit)
        assertNotNull(pace.etaToExhaustion)
    }

    @Test
    fun `en reserve et tient jusqu au reset`() {
        val pace = UsagePace.compute(0.1, now.plus(Duration.ofHours(1)), 300, now)!!
        assertEquals(UsagePace.Stage.FAR_BEHIND, pace.stage)
        assertTrue(pace.willLastToReset)
        assertNull(pace.etaToExhaustion)
    }

    @Test
    fun `rythme normal`() {
        val pace = UsagePace.compute(0.5, now.plus(Duration.ofMinutes(150)), 300, now)!!
        assertEquals(UsagePace.Stage.ON_TRACK, pace.stage)
    }
}
