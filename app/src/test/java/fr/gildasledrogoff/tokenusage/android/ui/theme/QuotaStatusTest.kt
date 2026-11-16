package fr.gildasledrogoff.tokenusage.ui.theme

import fr.gildasledrogoff.tokenusage.core.domain.model.UsageSeverity
import org.junit.Assert.assertEquals
import org.junit.Test

class QuotaStatusTest {

    @Test
    fun `remaining above 40 percent is healthy`() {
        assertEquals(QuotaStatus.HEALTHY, quotaStatusOf(0.41))
    }

    @Test
    fun `remaining between 15 and 40 percent is low, bounds included on the low side`() {
        assertEquals(QuotaStatus.LOW, quotaStatusOf(0.40))
        assertEquals(QuotaStatus.LOW, quotaStatusOf(0.16))
    }

    @Test
    fun `remaining at or below 15 percent is critical`() {
        assertEquals(QuotaStatus.CRITICAL, quotaStatusOf(0.15))
        assertEquals(QuotaStatus.CRITICAL, quotaStatusOf(0.0))
    }

    @Test
    fun `provider severity can only make the status stricter`() {
        assertEquals(QuotaStatus.LOW, quotaStatusOf(0.9, UsageSeverity.WARNING))
        assertEquals(QuotaStatus.CRITICAL, quotaStatusOf(0.9, UsageSeverity.CRITICAL))
        assertEquals(QuotaStatus.CRITICAL, quotaStatusOf(0.05, UsageSeverity.WARNING))
    }
}
