package fr.gildasledrogoff.tokenusage.core.network.zai

import fr.gildasledrogoff.tokenusage.core.network.zai.ZaiDto.displayName
import fr.gildasledrogoff.tokenusage.core.network.zai.ZaiDto.planName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZaiDtoTest {

    private fun limit(
        type: String,
        unit: Int,
        number: Int = 1
    ) = ZaiDto.QuotaLimit(type = type, unit = unit, number = number)

    @Test
    fun `token limit in hours is labelled Session`() {
        assertEquals("Session (5h)", limit("TOKENS_LIMIT", unit = ZaiDto.UNIT_HOURS, number = 5).displayName)
        assertEquals("Session (1h)", limit("TOKENS_LIMIT", unit = ZaiDto.UNIT_HOURS, number = 1).displayName)
        assertEquals("Session (8h)", limit("TOKENS_LIMIT", unit = ZaiDto.UNIT_HOURS, number = 8).displayName)
    }

    @Test
    fun `token limit in days is labelled Daily Quota`() {
        assertEquals("Daily Quota", limit("TOKENS_LIMIT", unit = ZaiDto.UNIT_DAYS, number = 1).displayName)
        assertEquals("7 Day Quota", limit("TOKENS_LIMIT", unit = ZaiDto.UNIT_DAYS, number = 7).displayName)
    }

    @Test
    fun `token limit in months is labelled Monthly Quota`() {
        assertEquals("Monthly Quota", limit("TOKENS_LIMIT", unit = ZaiDto.UNIT_MONTHS, number = 1).displayName)
    }

    @Test
    fun `time limit is labelled Tools`() {
        assertEquals("Monthly Tools", limit("TIME_LIMIT", unit = ZaiDto.UNIT_MONTHS, number = 1).displayName)
        assertEquals("Daily Tools", limit("TIME_LIMIT", unit = ZaiDto.UNIT_DAYS, number = 1).displayName)
    }

    @Test
    fun `unknown type falls back to raw type`() {
        assertEquals("SOMETHING_ELSE", limit("SOMETHING_ELSE", unit = 0).displayName)
    }

    @Test
    fun `planName maps levels`() {
        assertEquals("Lite", planName("lite"))
        assertEquals("Pro", planName("pro"))
        assertEquals("Max", planName("max"))
        assertNull(planName(""))
        assertEquals("custom", planName("custom"))
    }
}
