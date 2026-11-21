package fr.gildasledrogoff.tokenusage.core.security
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.ClaudeAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
class EncryptedPrefsManagerClaudeAccountsTest {
    private lateinit var store: InMemorySharedPreferences
    private lateinit var prefs: EncryptedPrefsManager
    @Before
    fun setup() {
        store = InMemorySharedPreferences()
        val factory = mock(SecureStorageFactory::class.java)
        `when`(factory.credentials).thenReturn(store)
        prefs = EncryptedPrefsManager(factory)
    }
    @Test
    fun `legacy single web session account is migrated with its org id`() {
        store.data["CLAUDE_access_token"] = "sk-ant-sid02-legacy"
        store.data["CLAUDE_auth_mode"] = "web_session"
        store.data["CLAUDE_org_id"] = "org-legacy"
        store.data["CLAUDE_5-Hour_resets_at"] = 1_700_000_000L
        val accounts = prefs.getClaudeAccounts()
        assertEquals(1, accounts.size)
        assertEquals(Credential.ClaudeWebCredential("sk-ant-sid02-legacy"), accounts[0].credential)
        assertEquals("org-legacy", prefs.getClaudeOrgId(accounts[0].id))
        assertFalse(store.data.containsKey("CLAUDE_access_token"))
        assertFalse(store.data.containsKey("CLAUDE_org_id"))
        assertTrue(store.data.containsKey("CLAUDE_5-Hour_resets_at"))
        assertEquals(accounts[0].id, prefs.getClaudeAccounts()[0].id)
    }
    @Test
    fun `legacy OAuth account keeps tokens and expiry`() {
        store.data["CLAUDE_access_token"] = "oauth-access"
        store.data["CLAUDE_refresh_token"] = "oauth-refresh"
        store.data["CLAUDE_expires_at"] = 1_800_000_000L
        store.data["CLAUDE_rate_limit_tier"] = "Max"
        val credential = prefs.getClaudeAccounts().single().credential as Credential.ClaudeCredential
        assertEquals("oauth-access", credential.accessToken)
        assertEquals("oauth-refresh", credential.refreshToken)
        assertEquals(Instant.ofEpochSecond(1_800_000_000L), credential.expiresAt)
        assertEquals("Max", credential.rateLimitTier)
    }
    @Test
    fun `primary account stands for the service`() {
        prefs.saveClaudeAccounts(
            listOf(
                ClaudeAccount("a", "Perso", Credential.ClaudeWebCredential("sk-ant-sid02-a")),
                ClaudeAccount("b", "Work", Credential.ClaudeWebCredential("sk-ant-sid02-b"))
            )
        )
        assertTrue(prefs.hasCredential(AiService.CLAUDE))
        assertEquals(Credential.ClaudeWebCredential("sk-ant-sid02-a"), prefs.loadCredential(AiService.CLAUDE))
    }
    @Test
    fun `org id survives a rename but not a new session key`() {
        val account = ClaudeAccount("a", "Perso", Credential.ClaudeWebCredential("sk-ant-sid02-a"))
        prefs.saveClaudeAccounts(listOf(account))
        prefs.saveClaudeOrgId("a", "org-a")
        prefs.saveClaudeAccounts(listOf(account.copy(label = "Personal")))
        assertEquals("org-a", prefs.getClaudeOrgId("a"))
        prefs.saveClaudeAccounts(listOf(account.copy(credential = Credential.ClaudeWebCredential("sk-ant-sid02-new"))))
        assertNull(prefs.getClaudeOrgId("a"))
    }
    @Test
    fun `deleting an account leaves the others`() {
        prefs.saveClaudeAccounts(
            listOf(
                ClaudeAccount("a", "", Credential.ClaudeWebCredential("sk-ant-sid02-a")),
                ClaudeAccount("b", "", Credential.ClaudeWebCredential("sk-ant-sid02-b"))
            )
        )
        prefs.deleteClaudeAccount("a")
        assertEquals(listOf("b"), prefs.getClaudeAccounts().map { it.id })
    }
}
