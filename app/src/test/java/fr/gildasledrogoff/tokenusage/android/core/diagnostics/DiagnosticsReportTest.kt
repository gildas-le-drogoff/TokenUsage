package fr.gildasledrogoff.tokenusage.core.diagnostics
import fr.gildasledrogoff.tokenusage.core.diagnostics.DiagnosticsReport.formatAge
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
class DiagnosticsReportTest {
    private val now = Instant.parse("2026-11-21T10:00:00Z")
    private val app = DiagnosticsReport.AppInfo("0.1.2", 12)
    private fun account(
        service: AiService = AiService.CLAUDE,
        status: DiagnosticStatus = DiagnosticStatus.OK,
        at: Instant? = now.minus(Duration.ofMinutes(12)),
        error: DiagnosticError? = null,
        lastError: DiagnosticError? = null,
        label: String? = null
    ) = AccountDiagnostic(
        accountKey = "${service.name}:default", service = service, accountLabel = label, status = status,
        at = at, origin = RefreshOrigin.PERIODIC_WORKER, error = error, lastError = lastError,
        lastErrorAt = lastError?.let { now.minusSeconds(3600) }
    )
    private val background = BackgroundSnapshot(
        works = listOf(
            WorkSnapshot(
                name = "quota_periodic_refresh", state = "ENQUEUED",
                nextRunAtEpochMs = now.plusSeconds(900).toEpochMilli(), runAttemptCount = 0,
                lastRun = WorkerRun("quota_periodic_refresh", now.minusSeconds(600).toEpochMilli(), "success")
            ),
            WorkSnapshot("token_periodic_refresh", "NOT_SCHEDULED", null, 0, null)
        ),
        ignoringBatteryOptimizations = false, standbyBucket = 40, backgroundRestricted = false,
        deviceIdle = false, powerSaveMode = true, manufacturer = "Xiaomi",
        manufacturerGroup = ManufacturerGroup.XIAOMI, sdkInt = 37
    )
    @Test
    fun `report lists accounts with status age origin and errors`() {
        val report = DiagnosticsReport.build(
            now, app,
            listOf(
                account(label = "Perso"),
                account(
                    AiService.CODEX, DiagnosticStatus.ERROR,
                    error = DiagnosticError("NetworkError", "timeout")
                ),
                account(AiService.GEMINI, DiagnosticStatus.NOT_CONFIGURED, at = null),
                account(AiService.ZAI, lastError = DiagnosticError("RateLimited"))
            ),
            background
        )
        assertTrue(report, report.contains("TokenUsage diagnostics"))
        assertTrue(report, report.contains("App: 0.1.2 (12) | Android SDK 37 | Manufacturer: Xiaomi"))
        assertTrue(report, report.contains("- Claude (Perso): OK | 2026-11-21T09:48:00Z (age 12m) | via periodic_worker"))
        assertTrue(report, report.contains("- Codex: ERROR"))
        assertTrue(report, report.contains("    error: NetworkError - timeout"))
        assertTrue(report, report.contains("- Gemini: not configured"))
        assertTrue(report, report.contains("    last error: RateLimited at 2026-11-21T09:00:00Z"))
    }
    @Test
    fun `report describes the background state`() {
        val report = DiagnosticsReport.build(now, app, emptyList(), background)
        assertTrue(report, report.contains("Battery optimizations ignored: no"))
        assertTrue(report, report.contains("App standby bucket: RARE"))
        assertTrue(report, report.contains("Battery saver: yes"))
        assertTrue(report, report.contains("Known aggressive vendor: xiaomi - https://dontkillmyapp.com/xiaomi"))
        assertTrue(report, report.contains("Work quota_periodic_refresh: ENQUEUED | next 2026-11-21T10:15:00Z | last run 2026-11-21T09:50:00Z (10m ago): success"))
        assertTrue(report, report.contains("Work token_periodic_refresh: NOT_SCHEDULED"))
    }
    @Test
    fun `report without background data omits the section`() {
        val report = DiagnosticsReport.build(now, app, listOf(account()), null)
        assertFalse(report.contains("== Background =="))
        assertFalse(report.contains("Android SDK"))
    }
    @Test
    fun `report never contains a secret even if one slipped into a stored message`() {
        val leaky = DiagnosticError("NetworkError", "Authorization: Bearer ya29.LEAKEDTOKENVALUE123 sessionKey=sk-ant-sid01-LEAKED")
        val report = DiagnosticsReport.build(
            now, app,
            listOf(account(status = DiagnosticStatus.ERROR, error = leaky, label = "me@example.invalid")),
            background
        )
        listOf("LEAKEDTOKENVALUE123", "sk-ant-sid01-LEAKED", "me@example.invalid").forEach {
            assertFalse("$it a fuité :\n$report", report.contains(it))
        }
    }
    @Test
    fun `unknown system values are shown as question marks`() {
        val report = DiagnosticsReport.build(
            now, app, emptyList(),
            background.copy(
                ignoringBatteryOptimizations = null, standbyBucket = null, backgroundRestricted = null,
                deviceIdle = null, powerSaveMode = null, manufacturer = "", manufacturerGroup = null
            )
        )
        assertTrue(report, report.contains("Battery optimizations ignored: ?"))
        assertTrue(report, report.contains("App standby bucket: ?"))
        assertTrue(report, report.contains("Manufacturer: ?"))
        assertFalse(report.contains("Known aggressive vendor"))
    }
    @Test
    fun `age formatting`() {
        assertEquals("-", formatAge(null))
        assertEquals("0s", formatAge(Duration.ZERO))
        assertEquals("59s", formatAge(Duration.ofSeconds(59)))
        assertEquals("1m", formatAge(Duration.ofSeconds(60)))
        assertEquals("12m", formatAge(Duration.ofMinutes(12)))
        assertEquals("59m", formatAge(Duration.ofSeconds(3_599)))
        assertEquals("1h00", formatAge(Duration.ofHours(1)))
        assertEquals("3h05", formatAge(Duration.ofMinutes(185)))
        assertEquals("1d", formatAge(Duration.ofHours(24)))
        assertEquals("3d", formatAge(Duration.ofDays(3).plusHours(23)))
        assertEquals("0s", formatAge(Duration.ofSeconds(-5)))
        assertNull(null)
    }
}
