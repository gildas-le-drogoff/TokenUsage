package fr.gildasledrogoff.tokenusage.feature.settings.accounts

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.provider.CredentialField
import fr.gildasledrogoff.tokenusage.core.provider.CredentialForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountsUiStateTest {
    private val form = CredentialForm(
        fields = listOf(
            CredentialField("apiKey", 0, secret = true),
            CredentialField("baseUrl", 0, secret = false)
        ),
        assemble = { _, _ -> null }
    )

    @Test
    fun `option key combines service and key`() {
        assertEquals("CLAUDE.beta", AccountsUiState.optionKey(AiService.CLAUDE, "beta"))
    }

    @Test
    fun `option defaults to false and reads the stored value`() {
        val state = AccountsUiState(options = mapOf(AccountsUiState.optionKey(AiService.CLAUDE, "beta") to true))
        assertTrue(state.option(AiService.CLAUDE, "beta"))
        assertFalse(state.option(AiService.CLAUDE, "other"))
        assertFalse(state.option(AiService.DEEPSEEK, "beta"))
    }

    @Test
    fun `an account is configured when a credential is stored`() {
        assertTrue(AccountUi("a", hasStoredCredential = true).isConfigured(form))
    }

    @Test
    fun `an account is configured when a secret field is typed`() {
        assertTrue(AccountUi("a", values = mapOf("apiKey" to "sk-1")).isConfigured(form))
    }

    @Test
    fun `blank secret or non secret values do not configure an account`() {
        assertFalse(AccountUi("a").isConfigured(form))
        assertFalse(AccountUi("a", values = mapOf("apiKey" to "  ")).isConfigured(form))
        assertFalse(AccountUi("a", values = mapOf("baseUrl" to "https://x")).isConfigured(form))
    }

    @Test
    fun `form exposes only the secret keys`() {
        assertEquals(setOf("apiKey"), form.secretKeys)
    }
}
