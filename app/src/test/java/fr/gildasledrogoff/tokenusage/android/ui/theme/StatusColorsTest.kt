package fr.gildasledrogoff.tokenusage.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StatusColorsTest {
    @Test
    fun `get returns the pair matching the status`() {
        listOf(LightStatusColors, DarkStatusColors).forEach { colors ->
            assertSame(colors.healthy, colors[QuotaStatus.HEALTHY])
            assertSame(colors.low, colors[QuotaStatus.LOW])
            assertSame(colors.critical, colors[QuotaStatus.CRITICAL])
        }
    }

    @Test
    fun `light and dark palettes differ for every status`() {
        QuotaStatus.entries.forEach {
            assertNotEquals(LightStatusColors[it], DarkStatusColors[it])
        }
    }

    @Test
    fun `each status has three distinct colors`() {
        listOf(LightStatusColors, DarkStatusColors).forEach { colors ->
            QuotaStatus.entries.forEach {
                val pair = colors[it]
                assertEquals(3, setOf(pair.main, pair.container, pair.onContainer).size)
            }
        }
    }

    @Test
    fun `statuses use different main colors`() {
        listOf(LightStatusColors, DarkStatusColors).forEach { colors ->
            assertEquals(3, QuotaStatus.entries.map { colors[it].main }.toSet().size)
        }
    }

    @Test
    fun `thresholds are ordered`() {
        assertEquals(true, CRITICAL_REMAINING_THRESHOLD < LOW_REMAINING_THRESHOLD)
    }

    @Test
    fun `statuses are ordered from healthy to critical`() {
        assertEquals(
            listOf(QuotaStatus.HEALTHY, QuotaStatus.LOW, QuotaStatus.CRITICAL),
            QuotaStatus.entries.sorted()
        )
    }
}
