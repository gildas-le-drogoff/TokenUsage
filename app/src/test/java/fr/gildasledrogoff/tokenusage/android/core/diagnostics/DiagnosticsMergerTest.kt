package fr.gildasledrogoff.tokenusage.core.diagnostics

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.repository.CachedQuotaResult
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class DiagnosticsMergerTest {

    private val t0 = Instant.parse("2026-11-21T10:00:00Z")

    private fun stored(
        service: AiService,
        number: Int? = null,
        at: Instant = t0,
        status: DiagnosticStatus = DiagnosticStatus.OK,
        origin: RefreshOrigin = RefreshOrigin.PERIODIC_WORKER,
        error: DiagnosticError? = null,
        lastError: DiagnosticError? = error,
        lastErrorAt: Instant? = if (error != null) at else null,
        label: String? = null
    ) = DiagnosticRecord(
        accountKey = DiagnosticRecord.keyOf(service, number),
        service = service,
        accountLabel = label,
        status = status,
        atEpochMs = at.toEpochMilli(),
        origin = origin,
        error = error,
        lastError = lastError,
        lastErrorAtEpochMs = lastErrorAt?.toEpochMilli()
    )

    private fun dashboard(at: Instant, vararg results: CachedQuotaResult) = LastFetchSnapshot(at, results.toList())

    private fun cachedOk(service: AiService, number: Int? = null, label: String? = null) =
        CachedQuotaResult(service, label, number, QuotaInfo(service, emptyList()), null)

    private fun cachedError(service: AiService, error: AppError, number: Int? = null) =
        CachedQuotaResult(service, null, number, null, error)

    private val everythingConfigured: (AiService) -> Boolean = { true }

    @Test
    fun `no data at all lists every service as not configured`() {
        val rows = DiagnosticsMerger.merge(emptyList(), null) { false }

        assertEquals(AiService.entries.size, rows.size)
        assertTrue(rows.all { it.status == DiagnosticStatus.NOT_CONFIGURED && it.at == null })
    }

    @Test
    fun `configured services never refreshed are flagged but not shown as OK`() {
        val rows = DiagnosticsMerger.merge(emptyList(), null) { it == AiService.CLAUDE }

        assertEquals(DiagnosticStatus.NEVER_REFRESHED, rows.single { it.service == AiService.CLAUDE }.status)
        assertEquals(DiagnosticStatus.NOT_CONFIGURED, rows.single { it.service == AiService.CODEX }.status)
    }

    @Test
    fun `stored records alone are reported`() {
        val rows = DiagnosticsMerger.merge(listOf(stored(AiService.CODEX)), null, everythingConfigured)

        val codex = rows.single { it.service == AiService.CODEX }
        assertEquals(DiagnosticStatus.OK, codex.status)
        assertEquals(RefreshOrigin.PERIODIC_WORKER, codex.origin)
        assertEquals(t0, codex.at)
    }

    @Test
    fun `dashboard alone is reported with the dashboard origin`() {
        val rows = DiagnosticsMerger.merge(emptyList(), dashboard(t0, cachedOk(AiService.GEMINI)), everythingConfigured)

        val gemini = rows.single { it.service == AiService.GEMINI }
        assertEquals(RefreshOrigin.DASHBOARD, gemini.origin)
        assertEquals(t0, gemini.at)
    }

    @Test
    fun `the most recent source wins`() {
        val older = stored(AiService.CODEX, at = t0.minusSeconds(600), origin = RefreshOrigin.PERIODIC_WORKER)
        val rowsNewerDashboard = DiagnosticsMerger.merge(listOf(older), dashboard(t0, cachedOk(AiService.CODEX)), everythingConfigured)
        assertEquals(RefreshOrigin.DASHBOARD, rowsNewerDashboard.single { it.service == AiService.CODEX }.origin)

        val newer = stored(AiService.CODEX, at = t0.plusSeconds(600), origin = RefreshOrigin.MANUAL_SERVICE)
        val rowsNewerStore = DiagnosticsMerger.merge(listOf(newer), dashboard(t0, cachedOk(AiService.CODEX)), everythingConfigured)
        assertEquals(RefreshOrigin.MANUAL_SERVICE, rowsNewerStore.single { it.service == AiService.CODEX }.origin)
    }

    @Test
    fun `an older worker error stays visible as last error behind a newer dashboard success`() {
        val error = DiagnosticError("NetworkError", "timeout")
        val worker = stored(
            AiService.CODEX, at = t0.minusSeconds(3600), status = DiagnosticStatus.ERROR, error = error
        )

        val row = DiagnosticsMerger.merge(listOf(worker), dashboard(t0, cachedOk(AiService.CODEX)), everythingConfigured)
            .single { it.service == AiService.CODEX }

        assertEquals(DiagnosticStatus.OK, row.status)
        assertNull(row.error)
        assertEquals(error, row.lastError)
        assertEquals(t0.minusSeconds(3600), row.lastErrorAt)
    }

    @Test
    fun `a newer dashboard error becomes the current error and the last error`() {
        val worker = stored(AiService.CODEX, at = t0.minusSeconds(60))

        val row = DiagnosticsMerger.merge(
            listOf(worker),
            dashboard(t0, cachedError(AiService.CODEX, AppError.RateLimited)),
            everythingConfigured
        ).single { it.service == AiService.CODEX }

        assertEquals(DiagnosticStatus.ERROR, row.status)
        assertEquals("RateLimited", row.error?.type)
        assertEquals("RateLimited", row.lastError?.type)
        assertEquals(t0, row.lastErrorAt)
    }

    @Test
    fun `a dashboard error older than the stored success only updates the last error`() {
        val worker = stored(AiService.CODEX, at = t0)

        val row = DiagnosticsMerger.merge(
            listOf(worker),
            dashboard(t0.minusSeconds(120), cachedError(AiService.CODEX, AppError.ServiceUnavailable)),
            everythingConfigured
        ).single { it.service == AiService.CODEX }

        assertEquals(DiagnosticStatus.OK, row.status)
        assertEquals(RefreshOrigin.PERIODIC_WORKER, row.origin)
        assertEquals("ServiceUnavailable", row.lastError?.type)
    }

    @Test
    fun `multiple accounts of one service produce one row each`() {
        val rows = DiagnosticsMerger.merge(
            listOf(stored(AiService.CLAUDE, number = 1, label = "A"), stored(AiService.CLAUDE, number = 2, label = "B")),
            null,
            everythingConfigured
        ).filter { it.service == AiService.CLAUDE }

        assertEquals(listOf("CLAUDE:1", "CLAUDE:2"), rows.map { it.accountKey })
        assertEquals(listOf("A", "B"), rows.map { it.accountLabel })
    }

    @Test
    fun `history of a service that is no longer configured is hidden`() {
        val rows = DiagnosticsMerger.merge(
            listOf(stored(AiService.CODEX)),
            dashboard(t0, cachedOk(AiService.CODEX)),
        ) { it != AiService.CODEX }

        val codex = rows.single { it.service == AiService.CODEX }
        assertEquals(DiagnosticStatus.NOT_CONFIGURED, codex.status)
        assertNull(codex.at)
    }

    @Test
    fun `rows are ordered by service then account`() {
        val rows = DiagnosticsMerger.merge(
            listOf(stored(AiService.ZAI, 2), stored(AiService.CLAUDE, 2), stored(AiService.CLAUDE, 1)),
            null,
            everythingConfigured
        )

        assertEquals(rows.sortedBy { it.service.ordinal }.map { it.service }, rows.map { it.service })
        val claude = rows.filter { it.service == AiService.CLAUDE }.map { it.accountKey }
        assertEquals(listOf("CLAUDE:1", "CLAUDE:2"), claude)
    }

    @Test
    fun `age is never negative`() {
        val row = DiagnosticsMerger.merge(listOf(stored(AiService.CODEX, at = t0)), null, everythingConfigured)
            .single { it.service == AiService.CODEX }

        assertEquals(Duration.ofMinutes(12), row.age(t0.plus(Duration.ofMinutes(12))))
        assertEquals(Duration.ZERO, row.age(t0.minusSeconds(30)))
        assertNull(DiagnosticsMerger.merge(emptyList(), null) { false }.first().age(t0))
    }

    @Test
    fun `dashboard snapshot without results is handled`() {
        val rows = DiagnosticsMerger.merge(emptyList(), LastFetchSnapshot(t0, null)) { false }

        assertEquals(AiService.entries.size, rows.size)
    }
}
