package fr.gildasledrogoff.tokenusage.core.diagnostics

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.security.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DiagnosticsStoreTest {

    private var now = Instant.parse("2026-11-21T10:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }
    private val prefs = InMemorySharedPreferences()
    private fun newStore() = DiagnosticsStore(lazyOf(prefs), clock)
    private val store = newStore()

    private val ok: Result<QuotaInfo, AppError> = Result.Success(QuotaInfo(AiService.CLAUDE, windows = emptyList()))
    private fun failure(error: AppError): Result<QuotaInfo, AppError> = Result.Failure(error)

    @Test
    fun `empty store has no records`() {
        assertTrue(store.records().isEmpty())
        assertTrue(store.workerRuns().isEmpty())
    }

    @Test
    fun `a success is recorded with its origin and time`() {
        store.recordFetch(RefreshOrigin.PERIODIC_WORKER, AiService.CLAUDE, "Perso", 1, ok)

        val record = store.records().single()
        assertEquals("CLAUDE:1", record.accountKey)
        assertEquals(DiagnosticStatus.OK, record.status)
        assertEquals(RefreshOrigin.PERIODIC_WORKER, record.origin)
        assertEquals(now.toEpochMilli(), record.atEpochMs)
        assertEquals("Perso", record.accountLabel)
        assertNull(record.error)
    }

    @Test
    fun `a single account service uses the default key`() {
        store.recordFetch(RefreshOrigin.MANUAL_SERVICE, AiService.CODEX, null, null, ok)

        assertEquals("CODEX:default", store.records().single().accountKey)
    }

    @Test
    fun `an error is recorded sanitized and survives a later success as last error`() {
        store.recordFetch(
            RefreshOrigin.PERIODIC_WORKER, AiService.GEMINI, null, null,
            failure(AppError.NetworkError("GET failed, Authorization: Bearer ya29.SECRETSECRETSECRET"))
        )
        val failed = store.records().single()
        assertEquals(DiagnosticStatus.ERROR, failed.status)
        assertEquals("NetworkError", failed.error?.type)
        assertFalse(failed.error!!.message.contains("SECRET"))

        now = now.plusSeconds(600)
        store.recordFetch(RefreshOrigin.DASHBOARD, AiService.GEMINI, null, null, ok)

        val recovered = store.records().single()
        assertEquals(DiagnosticStatus.OK, recovered.status)
        assertNull(recovered.error)
        assertEquals("NetworkError", recovered.lastError?.type)
        assertEquals(Instant.parse("2026-11-21T10:00:00Z").toEpochMilli(), recovered.lastErrorAtEpochMs)
    }

    @Test
    fun `newer error replaces the previous last error`() {
        store.recordFetch(RefreshOrigin.PERIODIC_WORKER, AiService.ZAI, null, null, failure(AppError.RateLimited))
        now = now.plusSeconds(60)
        store.recordFetch(RefreshOrigin.PERIODIC_WORKER, AiService.ZAI, null, null, failure(AppError.ServiceUnavailable))

        assertEquals("ServiceUnavailable", store.records().single().lastError?.type)
    }

    @Test
    fun `every AppError maps to a named type`() {
        val errors = listOf(
            AppError.NetworkError("x"),
            AppError.AuthError(AiService.CLAUDE, isTerminal = true, message = "expired"),
            AppError.RateLimited,
            AppError.ParseError("bad json"),
            AppError.CredentialNotFound(AiService.CLAUDE),
            AppError.TokenRevoked(AiService.CLAUDE),
            AppError.ServiceUnavailable,
            AppError.ClientUnsupported(AiService.GEMINI)
        )
        val types = errors.map { DiagnosticError.from(it).type }

        assertEquals(
            listOf(
                "NetworkError", "AuthError", "RateLimited", "ParseError",
                "CredentialNotFound", "TokenRevoked", "ServiceUnavailable", "ClientUnsupported"
            ),
            types
        )
        assertEquals("terminal: expired", DiagnosticError.from(errors[1]).message)
    }

    @Test
    fun `accounts of one service are kept apart`() {
        store.recordFetches(
            RefreshOrigin.PERIODIC_WORKER,
            listOf(
                DiagnosticsStore.FetchRecord(AiService.CLAUDE, "A", 1, ok),
                DiagnosticsStore.FetchRecord(AiService.CLAUDE, "B", 2, failure(AppError.RateLimited))
            )
        )

        val byKey = store.records().associateBy { it.accountKey }
        assertEquals(DiagnosticStatus.OK, byKey.getValue("CLAUDE:1").status)
        assertEquals(DiagnosticStatus.ERROR, byKey.getValue("CLAUDE:2").status)
    }

    @Test
    fun `labels that look like secrets or emails are sanitized`() {
        store.recordFetch(RefreshOrigin.MANUAL_SERVICE, AiService.CLAUDE, "jane@example.invalid", 1, ok)

        assertFalse(store.records().single().accountLabel!!.contains("jane"))
    }

    @Test
    fun `size is bounded and the most recent records are kept`() {
        repeat(100) { i ->
            now = now.plusSeconds(1)
            store.recordFetch(RefreshOrigin.MANUAL_SERVICE, AiService.CLAUDE, null, i, ok)
        }

        val records = store.records()
        assertEquals(64, records.size)
        assertTrue(records.any { it.accountKey == "CLAUDE:99" })
        assertFalse(records.any { it.accountKey == "CLAUDE:0" })
    }

    @Test
    fun `records survive a new store instance`() {
        store.recordFetch(RefreshOrigin.TILE, AiService.COPILOT, null, null, ok)

        assertEquals(RefreshOrigin.TILE, newStore().records().single().origin)
    }

    @Test
    fun `corrupted or future storage never throws`() {
        prefs.data["records"] = "{not json"
        prefs.data["worker_runs"] = "[1,2,3]"

        assertTrue(store.records().isEmpty())
        assertTrue(store.workerRuns().isEmpty())
        store.recordFetch(RefreshOrigin.MANUAL_SERVICE, AiService.CODEX, null, null, ok)
        assertEquals(1, store.records().size)
    }

    @Test
    fun `unknown json fields and enum values are tolerated`() {
        prefs.data["records"] = """[{"accountKey":"CODEX:default","service":"CODEX","status":"FUTURE_STATUS",
            "atEpochMs":1,"origin":"FUTURE_ORIGIN","brandNew":true}]"""

        val record = store.records().single()
        assertEquals(DiagnosticStatus.OK, record.status)
        assertEquals(RefreshOrigin.MANUAL_SERVICE, record.origin)
    }

    @Test
    fun `worker runs keep only the latest per work`() {
        store.recordWorkerRun("quota_periodic_refresh", "retry", 0)
        now = now.plusSeconds(30)
        store.recordWorkerRun("quota_periodic_refresh", "success", 1)
        store.recordWorkerRun("token_periodic_refresh", "gave_up", 3)

        val runs = store.workerRuns().associateBy { it.workName }
        assertEquals(2, runs.size)
        assertEquals("success", runs.getValue("quota_periodic_refresh").result)
        assertEquals(1, runs.getValue("quota_periodic_refresh").attempt)
        assertNotNull(runs["token_periodic_refresh"])
    }

    @Test
    fun `clear wipes everything`() {
        store.recordFetch(RefreshOrigin.MANUAL_SERVICE, AiService.CODEX, null, null, ok)
        store.recordWorkerRun("w", "success")

        store.clear()

        assertTrue(store.records().isEmpty())
        assertTrue(store.workerRuns().isEmpty())
    }

    @Test
    fun `empty batch writes nothing`() {
        store.recordFetches(RefreshOrigin.MANUAL_SERVICE, emptyList())

        assertFalse(prefs.contains("records"))
    }
}
