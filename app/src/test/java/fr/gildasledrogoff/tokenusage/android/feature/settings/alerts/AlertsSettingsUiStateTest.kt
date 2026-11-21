package fr.gildasledrogoff.tokenusage.feature.settings.alerts

import fr.gildasledrogoff.tokenusage.core.alert.AlertRule
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsSettingsUiStateTest {
    private fun account(n: Int) = AlertAccountUi("k$n", "A$n", n, AlertRule(), customized = false)

    private fun service(service: AiService, accounts: Int) =
        AlertServiceUi(service, AlertRule(), (1..accounts).map(::account), emptyList())

    @Test
    fun `several accounts are detected from two accounts`() {
        assertFalse(service(AiService.CLAUDE, 0).hasSeveralAccounts)
        assertFalse(service(AiService.CLAUDE, 1).hasSeveralAccounts)
        assertTrue(service(AiService.CLAUDE, 2).hasSeveralAccounts)
    }

    @Test
    fun `balance is supported by balance services only`() {
        assertEquals(setOf(AiService.DEEPSEEK, AiService.OPENROUTER), AlertServiceUi.BALANCE_SERVICES)
        assertTrue(service(AiService.DEEPSEEK, 1).supportsBalance)
        assertTrue(service(AiService.OPENROUTER, 1).supportsBalance)
        assertFalse(service(AiService.CLAUDE, 1).supportsBalance)
    }

    @Test
    fun `default state enables every option and lists nothing`() {
        val state = AlertsSettingsUiState()
        assertTrue(state.services.isEmpty())
        assertTrue(state.notificationsEnabled)
        assertTrue(state.warnBeforeReset)
        assertTrue(state.celebrateResets)
    }
}
