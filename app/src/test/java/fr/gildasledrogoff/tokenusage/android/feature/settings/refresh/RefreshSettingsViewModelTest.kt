package fr.gildasledrogoff.tokenusage.feature.settings.refresh
import app.cash.turbine.test
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.status.ProviderStatusRepository
import fr.gildasledrogoff.tokenusage.core.workmanager.FakeReconnectRefresh
import fr.gildasledrogoff.tokenusage.core.workmanager.RefreshScheduler
import fr.gildasledrogoff.tokenusage.feature.settings.FakeDisplayPreferences
import fr.gildasledrogoff.tokenusage.feature.settings.inMemoryPrefsManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.time.Clock
class RefreshSettingsViewModelTest {
    private val prefs = inMemoryPrefsManager()
    private val scheduler = mock(RefreshScheduler::class.java)
    private val displayPreferences = FakeDisplayPreferences()
    private val statusRepository = ProviderStatusRepository(Clock.systemUTC(), prefs).apply {
        fetcher = { _, _ -> null }
    }
    private val reconnectRefresh = FakeReconnectRefresh()
    private fun viewModel() =
        RefreshSettingsViewModel(prefs, scheduler, displayPreferences, statusRepository, reconnectRefresh)

    @Test
    fun `the reconnect refresh is off until the user accepts it`() {
        val vm = viewModel()
        assertFalse(vm.uiState.value.reconnectRefreshEnabled)
        vm.setReconnectRefreshEnabled(true)
        assertTrue(vm.uiState.value.reconnectRefreshEnabled)
        assertTrue(reconnectRefresh.isEnabled())
        assertTrue("a new screen reads the saved choice", viewModel().uiState.value.reconnectRefreshEnabled)
        vm.setReconnectRefreshEnabled(false)
        assertFalse(reconnectRefresh.isEnabled())
    }

    @Test
    fun `initial state comes from the preferences`() {
        prefs.setRefreshInterval(60)
        prefs.setNotificationsEnabled(false)
        val state = viewModel().uiState.value
        assertEquals(60L, state.refreshIntervalMinutes)
        assertFalse(state.notificationsEnabled)
    }
    @Test
    fun `defaults without saved preferences`() {
        val state = viewModel().uiState.value
        assertEquals(prefs.getRefreshInterval(), state.refreshIntervalMinutes)
        assertEquals(prefs.isNotificationsEnabled(), state.notificationsEnabled)
    }
    @Test
    fun `interval change is saved and scheduled`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitItem()
            viewModel.setRefreshInterval(15)
            assertEquals(15L, awaitItem().refreshIntervalMinutes)
        }
        assertEquals(15L, prefs.getRefreshInterval())
        verify(scheduler).apply(15L)
    }
    @Test
    fun `manual only interval is scheduled as zero`() {
        val viewModel = viewModel()
        viewModel.setRefreshInterval(0)
        verify(scheduler).apply(0L)
        assertEquals(0L, viewModel.uiState.value.refreshIntervalMinutes)
    }
    @Test
    fun `notifications toggle is saved`() {
        val viewModel = viewModel()
        viewModel.setNotificationsEnabled(false)
        assertFalse(prefs.isNotificationsEnabled())
        assertFalse(viewModel.uiState.value.notificationsEnabled)
        viewModel.setNotificationsEnabled(true)
        assertTrue(prefs.isNotificationsEnabled())
        assertTrue(viewModel.uiState.value.notificationsEnabled)
    }
    @Test
    fun `answering the battery prompt is remembered`() {
        val viewModel = viewModel()
        assertFalse(displayPreferences.batteryPromptAnswered)
        viewModel.onBatteryChoiceMade()
        assertTrue(displayPreferences.batteryPromptAnswered)
    }

    @Test
    fun `status check is off by default`() {
        val state = viewModel().uiState.value.statusCheck
        assertFalse(state.allProviders)
        assertFalse(state.isAnyEnabled)
    }

    @Test
    fun `status check can be enabled for all providers`() {
        val viewModel = viewModel()
        viewModel.setStatusCheckForAll(true)
        assertTrue(viewModel.uiState.value.statusCheck.isEnabled(AiService.COPILOT))
        assertTrue(prefs.getStatusCheckSettings().allProviders)
    }

    @Test
    fun `status check can be enabled per provider`() {
        val viewModel = viewModel()
        viewModel.setStatusCheckForProvider(AiService.CLAUDE, true)
        val state = viewModel.uiState.value.statusCheck
        assertTrue(state.isEnabled(AiService.CLAUDE))
        assertFalse(state.isEnabled(AiService.CODEX))
        assertEquals(setOf(AiService.CLAUDE), prefs.getStatusCheckSettings().providers)
        viewModel.setStatusCheckForProvider(AiService.CLAUDE, false)
        assertFalse(viewModel.uiState.value.statusCheck.isAnyEnabled)
    }
}
