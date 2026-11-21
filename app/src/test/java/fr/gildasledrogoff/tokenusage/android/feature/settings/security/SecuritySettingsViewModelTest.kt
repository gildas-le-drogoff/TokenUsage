package fr.gildasledrogoff.tokenusage.feature.settings.security
import app.cash.turbine.test
import fr.gildasledrogoff.tokenusage.core.alert.AlertPreferencesStore
import fr.gildasledrogoff.tokenusage.core.alert.AlertRule
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.security.AppLockManager
import fr.gildasledrogoff.tokenusage.core.security.InMemorySharedPreferences
import fr.gildasledrogoff.tokenusage.feature.settings.accounts.AccountsSync
import fr.gildasledrogoff.tokenusage.feature.settings.inMemoryPrefsManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
class SecuritySettingsViewModelTest {
    private val store = InMemorySharedPreferences()
    private val prefs = inMemoryPrefsManager(store)
    private val appLock = mock(AppLockManager::class.java)
    private val demoData = mock(DemoDataRepository::class.java)
    private val accountsSync = AccountsSync()
    private val alertStore = AlertPreferencesStore { store }
    @Before
    fun setUp() {
        `when`(appLock.isEnabled()).thenReturn(false)
        `when`(demoData.isAvailable).thenReturn(true)
        `when`(demoData.isEnabled()).thenReturn(false)
    }
    private fun viewModel() = SecuritySettingsViewModel(prefs, appLock, demoData, accountsSync, alertStore)
    @Test
    fun `initial state reflects the lock and the sample data`() {
        `when`(appLock.isEnabled()).thenReturn(true)
        `when`(demoData.isEnabled()).thenReturn(true)
        val state = viewModel().uiState.value
        assertTrue(state.appLockEnabled)
        assertTrue(state.demoDataAvailable)
        assertTrue(state.demoDataEnabled)
        assertFalse(state.showDeleteConfirmDialog)
    }
    @Test
    fun `app lock toggle is forwarded and published`() {
        val viewModel = viewModel()
        viewModel.setAppLockEnabled(true)
        verify(appLock).setEnabled(true)
        assertTrue(viewModel.uiState.value.appLockEnabled)
    }
    @Test
    fun `sample data state is read back from the repository`() {
        val viewModel = viewModel()
        viewModel.setDemoDataEnabled(true)
        verify(demoData).setEnabled(true)
        assertFalse(viewModel.uiState.value.demoDataEnabled)
        `when`(demoData.isEnabled()).thenReturn(true)
        viewModel.setDemoDataEnabled(true)
        assertTrue(viewModel.uiState.value.demoDataEnabled)
    }
    @Test
    fun `delete dialog opens and closes`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitItem()
            viewModel.showDeleteConfirmDialog()
            assertTrue(awaitItem().showDeleteConfirmDialog)
            viewModel.dismissDeleteConfirmDialog()
            assertFalse(awaitItem().showDeleteConfirmDialog)
        }
    }
    @Test
    fun `deleting everything wipes the credentials and the alert rules`() = runTest {
        prefs.setNotificationsEnabled(false)
        alertStore.setServiceRule(AiService.CLAUDE, AlertRule(quotaThreshold = 0.5))
        val viewModel = viewModel()
        viewModel.setAppLockEnabled(true)
        viewModel.showDeleteConfirmDialog()
        accountsSync.externalChanges.test {
            viewModel.deleteAllCredentials()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(store.data.isEmpty() || store.data.keys.none { it.startsWith("alert_") })
        assertEquals(0.8, alertStore.currentConfig().ruleFor(AiService.CLAUDE, "CLAUDE:x").quotaThreshold!!, 1e-9)
        assertTrue(prefs.isNotificationsEnabled())
        val state = viewModel.uiState.value
        assertFalse(state.showDeleteConfirmDialog)
        assertTrue(state.appLockEnabled)
        assertTrue(state.demoDataAvailable)
    }
}
