package fr.gildasledrogoff.tokenusage.feature.diagnostics

import fr.gildasledrogoff.tokenusage.core.diagnostics.BackgroundSnapshot
import fr.gildasledrogoff.tokenusage.core.diagnostics.BackgroundStateSource
import fr.gildasledrogoff.tokenusage.core.diagnostics.DiagnosticStatus
import fr.gildasledrogoff.tokenusage.core.diagnostics.DiagnosticsReport
import fr.gildasledrogoff.tokenusage.core.diagnostics.DiagnosticsStore
import fr.gildasledrogoff.tokenusage.core.diagnostics.RefreshOrigin
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.repository.CachedQuotaResult
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchSnapshot
import fr.gildasledrogoff.tokenusage.core.security.InMemorySharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-11-21T10:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private var snapshot: LastFetchSnapshot? = null
    private var snapshotFailure: Exception? = null
    private val lastFetch = object : LastFetchRepository {
        override suspend fun snapshot(): LastFetchSnapshot? {
            snapshotFailure?.let { throw it }
            return snapshot
        }
        override suspend fun record(fetchedAt: Instant, results: List<CachedQuotaResult>) = Unit
    }

    private var background: BackgroundSnapshot? = snapshotOfBackground()
    private val backgroundSource = BackgroundStateSource {
        background ?: error("system unavailable")
    }

    private val prefs = InMemorySharedPreferences()
    private val store = DiagnosticsStore(lazyOf(prefs), clock)
    private var configured: Set<AiService> = setOf(AiService.CLAUDE, AiService.CODEX)

    private fun snapshotOfBackground() = BackgroundSnapshot(
        works = emptyList(), ignoringBatteryOptimizations = true, standbyBucket = 10,
        backgroundRestricted = false, deviceIdle = false, powerSaveMode = false,
        manufacturer = "Google", manufacturerGroup = null, sdkInt = 37
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun newViewModel() = DiagnosticsViewModel(lastFetch, store, backgroundSource, { configured.contains(it) }, clock)

    @Test
    fun `initial state is loading then loaded`() = runTest(dispatcher) {
        val vm = newViewModel()
        assertTrue(vm.state.value.loading)

        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(now, state.generatedAt)
        assertEquals(AiService.entries.size, state.accounts.size)
        assertEquals(DiagnosticStatus.NEVER_REFRESHED, state.accounts.single { it.service == AiService.CLAUDE }.status)
        assertEquals(DiagnosticStatus.NOT_CONFIGURED, state.accounts.single { it.service == AiService.GEMINI }.status)
        assertEquals(10, state.background?.standbyBucket)
    }

    @Test
    fun `dashboard snapshot and background journal are merged`() = runTest(dispatcher) {
        snapshot = LastFetchSnapshot(
            now.minusSeconds(300),
            listOf(CachedQuotaResult(AiService.CLAUDE, null, null, QuotaInfo(AiService.CLAUDE, emptyList()), null))
        )
        store.recordFetch(
            RefreshOrigin.PERIODIC_WORKER, AiService.CODEX, null, null,
            Result.Failure(AppError.NetworkError("offline"))
        )

        val vm = newViewModel()
        advanceUntilIdle()

        val claude = vm.state.value.accounts.single { it.service == AiService.CLAUDE }
        val codex = vm.state.value.accounts.single { it.service == AiService.CODEX }
        assertEquals(RefreshOrigin.DASHBOARD, claude.origin)
        assertEquals(DiagnosticStatus.OK, claude.status)
        assertEquals(DiagnosticStatus.ERROR, codex.status)
        assertEquals("NetworkError", codex.error?.type)
    }

    @Test
    fun `a failing dashboard cache does not break the screen`() = runTest(dispatcher) {
        snapshotFailure = IllegalStateException("db closed")

        val vm = newViewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.loading)
        assertEquals(AiService.entries.size, vm.state.value.accounts.size)
    }

    @Test
    fun `an unreadable system state leaves the background section empty`() = runTest(dispatcher) {
        background = null

        val vm = newViewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.background)
    }

    @Test
    fun `refresh picks up new journal entries`() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        assertEquals(DiagnosticStatus.NEVER_REFRESHED, vm.state.value.accounts.single { it.service == AiService.CLAUDE }.status)

        store.recordFetch(RefreshOrigin.MANUAL_SERVICE, AiService.CLAUDE, null, null, Result.Success(QuotaInfo(AiService.CLAUDE, emptyList())))
        vm.refresh()
        assertTrue(vm.state.value.loading)
        advanceUntilIdle()

        assertEquals(DiagnosticStatus.OK, vm.state.value.accounts.single { it.service == AiService.CLAUDE }.status)
    }

    @Test
    fun `report is built from the current state and is free of secrets`() = runTest(dispatcher) {
        store.recordFetch(
            RefreshOrigin.PERIODIC_WORKER, AiService.CLAUDE, null, null,
            Result.Failure(AppError.NetworkError("Authorization: Bearer ya29.SUPERSECRETTOKEN0123"))
        )
        val vm = newViewModel()
        advanceUntilIdle()

        val report = vm.buildReport(DiagnosticsReport.AppInfo("0.1.2", 12))

        assertTrue(report, report.contains("- Claude: ERROR"))
        assertTrue(report, report.contains("NetworkError"))
        assertFalse(report, report.contains("SUPERSECRETTOKEN0123"))
    }
}
