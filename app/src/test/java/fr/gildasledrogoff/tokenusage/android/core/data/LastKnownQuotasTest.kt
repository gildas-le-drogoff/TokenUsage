package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.domain.repository.CachedQuotaResult
import fr.gildasledrogoff.tokenusage.core.domain.repository.keepingLastKnownQuotas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant

class LastKnownQuotasTest {
    private val earlier = Instant.parse("2026-11-21T08:00:00Z")
    private val later = Instant.parse("2026-11-21T09:00:00Z")

    private fun quota(service: AiService, id: String, utilization: Double, at: Instant) =
        QuotaInfo(service, listOf(UsageWindow("5h", utilization, resetsAt = null)), fetchedAt = at, accountId = id)

    private fun ok(service: AiService, id: String, utilization: Double, at: Instant) =
        CachedQuotaResult(service, quota = quota(service, id, utilization, at), error = null, accountId = id)

    private fun failed(service: AiService, id: String?, error: AppError = AppError.RateLimited) =
        CachedQuotaResult(service, quota = null, error = error, accountId = id)

    @Test
    fun `without a previous fetch nothing changes`() {
        val current = listOf(failed(AiService.CLAUDE, "a1"))
        assertSame(current, current.keepingLastKnownQuotas(null))
        assertSame(current, current.keepingLastKnownQuotas(emptyList()))
    }

    @Test
    fun `a failing account keeps its last quota and the new error`() {
        val previous = listOf(ok(AiService.CLAUDE, "a1", 0.3, earlier))
        val merged = listOf(failed(AiService.CLAUDE, "a1")).keepingLastKnownQuotas(previous)
        assertEquals(AppError.RateLimited, merged.single().error)
        assertEquals(earlier, merged.single().quota!!.fetchedAt)
        assertEquals(0.3, merged.single().quota!!.windows.single().utilization, 0.0)
    }

    @Test
    fun `successful results replace the previous quota`() {
        val previous = listOf(ok(AiService.CLAUDE, "a1", 0.3, earlier))
        val fresh = ok(AiService.CLAUDE, "a1", 0.6, later)
        assertEquals(listOf(fresh), listOf(fresh).keepingLastKnownQuotas(previous))
    }

    @Test
    fun `a stale quota is carried over across several failures`() {
        val first = listOf(failed(AiService.ZAI, "z1")).keepingLastKnownQuotas(listOf(ok(AiService.ZAI, "z1", 0.2, earlier)))
        val second = listOf(failed(AiService.ZAI, "z1", AppError.ServiceUnavailable)).keepingLastKnownQuotas(first)
        assertEquals(AppError.ServiceUnavailable, second.single().error)
        assertEquals(earlier, second.single().quota!!.fetchedAt)
    }

    @Test
    fun `quotas are matched by service and account id`() {
        val previous = listOf(
            ok(AiService.CLAUDE, "shared-id", 0.1, earlier),
            ok(AiService.CODEX, "c1", 0.2, earlier)
        )
        val merged = listOf(
            failed(AiService.CODEX, "shared-id"),
            failed(AiService.CODEX, "c2"),
            failed(AiService.CODEX, null)
        ).keepingLastKnownQuotas(previous)
        merged.forEach { assertNull(it.quota) }
    }

    @Test
    fun `accounts no longer configured are dropped`() {
        val previous = listOf(ok(AiService.CLAUDE, "a1", 0.3, earlier), ok(AiService.CLAUDE, "gone", 0.5, earlier))
        val merged = listOf(failed(AiService.CLAUDE, "a1")).keepingLastKnownQuotas(previous)
        assertEquals(listOf("a1"), merged.map { it.accountId })
    }
}
