package fr.gildasledrogoff.tokenusage.feature.settings.alerts
import fr.gildasledrogoff.tokenusage.core.alert.AlertPreferencesStore
import fr.gildasledrogoff.tokenusage.core.alert.AlertRule
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.domain.repository.CachedQuotaResult
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchSnapshot
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.security.InMemorySharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
@OptIn(ExperimentalCoroutinesApi::class)
class AlertsSettingsViewModelTest {
    private val backing = InMemorySharedPreferences()
    private val store = AlertPreferencesStore { backing }
    private val prefs = mock(EncryptedPrefsManager::class.java)
    private var snapshot: LastFetchSnapshot? = null
    private val lastFetch = object : LastFetchRepository {
        override suspend fun snapshot() = snapshot
        override suspend fun record(fetchedAt: Instant, results: List<CachedQuotaResult>) = Unit
    }
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        `when`(prefs.isNotificationsEnabled()).thenReturn(true)
    }
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }
    private fun account(id: String, label: String = "") =
        ServiceAccount(id, label, Credential.ApiKeyCredential("key-$id"))
    private fun accounts(service: AiService, vararg accounts: ServiceAccount) {
        `when`(prefs.getAccounts(service)).thenReturn(accounts.toList())
    }
    private fun viewModel() = AlertsSettingsViewModel(store, prefs, lastFetch)
    @Test
    fun `only services with an account are listed`() {
        accounts(AiService.CODEX, account("c1"))
        accounts(AiService.DEEPSEEK, account("d1", "Perso"), account("d2"))
        val state = viewModel().uiState.value
        assertEquals(listOf(AiService.CODEX, AiService.DEEPSEEK), state.services.map { it.service })
        val deepseek = state.services.last()
        assertTrue(deepseek.hasSeveralAccounts)
        assertEquals(listOf("DEEPSEEK:d1", "DEEPSEEK:d2"), deepseek.accounts.map { it.key })
        assertEquals(listOf("Perso", null), deepseek.accounts.map { it.label })
        assertEquals(listOf(1, 2), deepseek.accounts.map { it.number })
        assertFalse(state.services.first().hasSeveralAccounts)
    }
    @Test
    fun `no account gives an empty list`() {
        assertTrue(viewModel().uiState.value.services.isEmpty())
    }
    @Test
    fun `balance rule only for services reporting a balance`() {
        accounts(AiService.DEEPSEEK, account("d1"))
        accounts(AiService.CODEX, account("c1"))
        val services = viewModel().uiState.value.services.associateBy { it.service }
        assertTrue(services.getValue(AiService.DEEPSEEK).supportsBalance)
        assertFalse(services.getValue(AiService.CODEX).supportsBalance)
    }
    @Test
    fun `notifications state is exposed`() {
        `when`(prefs.isNotificationsEnabled()).thenReturn(false)
        assertFalse(viewModel().uiState.value.notificationsEnabled)
    }
    @Test
    fun `service rule change is stored and reflected in the state`() {
        accounts(AiService.CLAUDE, account("a1"))
        val viewModel = viewModel()
        viewModel.updateServiceRule(AiService.CLAUDE, AlertRule(quotaThreshold = 0.6, depletionHorizonHours = 12))
        val entry = viewModel.uiState.value.services.single()
        assertEquals(0.6, entry.defaultRule.quotaThreshold!!, 1e-9)
        assertEquals(12, entry.defaultRule.depletionHorizonHours)
        assertEquals(entry.defaultRule, entry.accounts.single().rule)
        assertEquals(0.6, store.currentConfig().ruleFor(AiService.CLAUDE, "CLAUDE:a1").quotaThreshold!!, 1e-9)
    }
    @Test
    fun `customizing an account copies the default then can be undone`() {
        accounts(AiService.CLAUDE, account("a1"), account("a2"))
        val viewModel = viewModel()
        viewModel.updateServiceRule(AiService.CLAUDE, AlertRule(quotaThreshold = 0.7))
        viewModel.setAccountCustomized(AiService.CLAUDE, "CLAUDE:a2", true)
        var accounts = viewModel.uiState.value.services.single().accounts
        assertFalse(accounts[0].customized)
        assertTrue(accounts[1].customized)
        assertEquals(0.7, accounts[1].rule.quotaThreshold!!, 1e-9)
        viewModel.updateAccountRule("CLAUDE:a2", AlertRule(quotaThreshold = 0.95))
        accounts = viewModel.uiState.value.services.single().accounts
        assertEquals(0.95, accounts[1].rule.quotaThreshold!!, 1e-9)
        assertEquals(0.7, accounts[0].rule.quotaThreshold!!, 1e-9)
        viewModel.setAccountCustomized(AiService.CLAUDE, "CLAUDE:a2", false)
        accounts = viewModel.uiState.value.services.single().accounts
        assertFalse(accounts[1].customized)
        assertEquals(0.7, accounts[1].rule.quotaThreshold!!, 1e-9)
    }
    @Test
    fun `window labels come from the last refresh`() {
        accounts(AiService.CLAUDE, account("a1"))
        snapshot = LastFetchSnapshot(
            fetchedAt = Instant.EPOCH,
            results = listOf(
                CachedQuotaResult(
                    service = AiService.CLAUDE,
                    quota = QuotaInfo(
                        service = AiService.CLAUDE,
                        windows = listOf(UsageWindow("5-Hour", 0.1, null), UsageWindow("7-Day", 0.2, null))
                    ),
                    error = null
                ),
                CachedQuotaResult(service = AiService.CODEX, quota = null, error = null)
            )
        )
        assertEquals(listOf("5-Hour", "7-Day"), viewModel().uiState.value.services.single().windowLabels)
    }
    @Test
    fun `no last refresh means no window labels`() {
        accounts(AiService.CLAUDE, account("a1"))
        assertTrue(viewModel().uiState.value.services.single().windowLabels.isEmpty())
    }
    @Test
    fun `reload picks up accounts added elsewhere`() {
        accounts(AiService.CLAUDE, account("a1"))
        val viewModel = viewModel()
        assertEquals(1, viewModel.uiState.value.services.size)
        accounts(AiService.CODEX, account("c1"))
        viewModel.reload()
        assertEquals(2, viewModel.uiState.value.services.size)
    }
    @Test
    fun `default rule of a service without stored rule is the standard one`() {
        accounts(AiService.GEMINI, account("g1"))
        val rule = viewModel().uiState.value.services.single().defaultRule
        assertEquals(AlertRule(), rule)
        assertNull(rule.balanceThreshold)
    }
}
