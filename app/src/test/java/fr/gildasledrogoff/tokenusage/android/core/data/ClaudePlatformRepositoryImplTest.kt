package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import fr.gildasledrogoff.tokenusage.core.network.claudeplatform.ClaudePlatformDto.Credits
import fr.gildasledrogoff.tokenusage.core.network.claudeplatform.ClaudePlatformDto.Tranche
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ClaudePlatformRepositoryImplTest {
    private val now = Instant.parse("2026-11-21T12:00:00Z")

    @Test
    fun `session cookie accepts a bare value or a full header`() {
        assertEquals("sessionKey=abc", ClaudePlatformRepositoryImpl.sessionCookie(" abc "))
        assertEquals("a=1; sessionKey=abc", ClaudePlatformRepositoryImpl.sessionCookie("Cookie: a=1; sessionKey=abc"))
        assertNull(ClaudePlatformRepositoryImpl.sessionCookie("  "))
    }

    @Test
    fun `balance, grants and spend are scaled by the exponent`() {
        val credits = Credits(
            amount = 12475,
            promoTranches = listOf(
                Tranche("Max", "subscription_api_credit", 20000, 12475, "2026-12-28T00:00:00Z"),
                Tranche("Old", "promo", 1000, 500, "2026-01-01T00:00:00Z")
            ),
            tranches = listOf(Tranche(null, null, 5000, 5000, null))
        )
        val quota = ClaudePlatformRepositoryImpl.toQuotaInfo(credits, 1234, "Org", now)
        assertEquals(124.75, quota.accountBalances.single().balance!!.totalBalance, 1e-9)
        assertEquals(listOf("API credit", "Purchased credit"), quota.windows.map { it.label })
        assertEquals(0.37625, quota.windows[0].utilization, 1e-9)
        assertEquals(Instant.parse("2026-12-28T00:00:00Z"), quota.windows[0].resetsAt)
        assertEquals(0.0, quota.windows[1].utilization, 1e-9)
        assertEquals(SpendPeriod.THIS_MONTH, quota.spending.single().period)
        assertEquals(12.34, quota.spending.single().amount, 1e-9)
        assertEquals("Org", quota.tier)
    }

    @Test
    fun `exhausted subscription grant gives no lane and missing spend is omitted`() {
        val credits = Credits(
            amount = 0,
            promoTranches = listOf(Tranche("Max", "subscription_api_credit", 100, 0, null))
        )
        val quota = ClaudePlatformRepositoryImpl.toQuotaInfo(credits, null, null, now)
        assertEquals(0.0, quota.accountBalances.single().balance!!.totalBalance, 1e-9)
        assertEquals(emptyList<String>(), quota.windows.map { it.label })
        assertEquals(emptyList<SpendPeriod>(), quota.spending.map { it.period })
    }
}
