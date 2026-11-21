package fr.gildasledrogoff.tokenusage.core.security

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class EncryptedPrefsManagerAccountsTest {

    private lateinit var store: InMemorySharedPreferences
    private lateinit var prefs: EncryptedPrefsManager

    @Before
    fun setup() {
        store = InMemorySharedPreferences()
        val factory = mock(SecureStorageFactory::class.java)
        `when`(factory.credentials).thenReturn(store)
        prefs = EncryptedPrefsManager(factory)
    }

    private fun key(value: String) = Credential.ApiKeyCredential(value)

    // --- migration of the pre-multi-account storage -------------------------------------------------

    @Test
    fun `legacy codex credential becomes the first account without loss`() {
        store.data["CODEX_access_token"] = "at"
        store.data["CODEX_refresh_token"] = "rt"
        store.data["CODEX_account_id"] = "acct-1"
        store.data["CODEX_5-Hour_resets_at"] = 1_700_000_000L

        val accounts = prefs.getAccounts(AiService.CODEX)

        assertEquals(1, accounts.size)
        assertEquals(Credential.CodexCredential("at", "rt", "acct-1"), accounts[0].credential)
        assertEquals("", accounts[0].label)
        assertFalse(store.data.containsKey("CODEX_access_token"))
        assertFalse(store.data.containsKey("CODEX_refresh_token"))
        assertFalse(store.data.containsKey("CODEX_account_id"))
        assertTrue("reset times are not credentials", store.data.containsKey("CODEX_5-Hour_resets_at"))
        assertEquals("migration is stable: same id on the next read", accounts[0].id, prefs.getAccounts(AiService.CODEX)[0].id)
    }

    @Test
    fun `legacy gemini credential keeps tokens, client and hosted domain`() {
        store.data["GEMINI_access_token"] = "at"
        store.data["GEMINI_refresh_token"] = "rt"
        store.data["GEMINI_expires_at_ms"] = 1_900_000_000_000L
        store.data["GEMINI_oauth_client_id"] = "cid"
        store.data["GEMINI_oauth_client_secret"] = "secret"
        store.data["GEMINI_hosted_domain"] = "example.com"

        val credential = prefs.getAccounts(AiService.GEMINI).single().credential

        assertEquals(
            Credential.GeminiCredential("at", "rt", 1_900_000_000_000L, "cid", "secret", "example.com"),
            credential
        )
        assertTrue(store.data.keys.none { it.startsWith("GEMINI_") && it != "GEMINI_accounts" })
    }

    @Test
    fun `legacy single api key services migrate to an api key account`() {
        for (service in listOf(AiService.COPILOT, AiService.OPENROUTER, AiService.OPENAI)) {
            store.data["${service.name}_access_token"] = "key-${service.name}"
        }

        for (service in listOf(AiService.COPILOT, AiService.OPENROUTER, AiService.OPENAI)) {
            assertEquals(key("key-${service.name}"), prefs.getAccounts(service).single().credential)
        }
    }

    @Test
    fun `an incomplete legacy gemini credential is left untouched`() {
        store.data["GEMINI_access_token"] = "at"
        store.data["GEMINI_oauth_client_id"] = "cid"

        assertTrue(prefs.getAccounts(AiService.GEMINI).isEmpty())
        assertEquals("at", store.data["GEMINI_access_token"])
    }

    @Test
    fun `nothing stored means no account`() {
        AiService.entries.forEach { assertTrue("$it", prefs.getAccounts(it).isEmpty()) }
        assertFalse(prefs.hasCredential(AiService.OPENAI))
        assertNull(prefs.loadCredential(AiService.OPENAI))
    }

    // --- N accounts per service -------------------------------------------------------------------------

    @Test
    fun `every service accepts several accounts`() {
        val samples = mapOf(
            AiService.CLAUDE to listOf(
                Credential.ClaudeWebCredential("sk-ant-sid02-a"),
                Credential.ClaudeCredential("oauth", "refresh")
            ),
            AiService.CODEX to listOf(
                Credential.CodexCredential("a1", "r1", null),
                Credential.CodexCredential("a2", "r2", "acct")
            ),
            AiService.GEMINI to listOf(
                Credential.GeminiCredential("a1", "r1", 5L, "c1", "s1"),
                Credential.GeminiCredential("a2", "r2", 6L, "c2", "s2", "corp.com")
            ),
            AiService.DEEPSEEK to listOf(Credential.DeepseekCredential("d1"), Credential.DeepseekCredential("d2")),
            AiService.ZAI to listOf(Credential.ZaiCredential("z1"), Credential.ZaiCredential("z2")),
            AiService.COPILOT to listOf(key("c1"), key("c2")),
            AiService.OPENROUTER to listOf(key("o1"), key("o2")),
            AiService.OPENAI to listOf(key("p1"), key("p2"))
        )
        assertEquals("a sample per service", AiService.entries.toSet(), samples.keys)

        for ((service, credentials) in samples) {
            val first = prefs.addAccount(service, "First", credentials[0])
            val second = prefs.addAccount(service, "", credentials[1])

            val stored = prefs.getAccounts(service)
            assertEquals("$service ids", listOf(first.id, second.id), stored.map { it.id })
            assertEquals("$service labels", listOf("First", ""), stored.map { it.label })
            assertEquals("$service credentials", credentials, stored.map { it.credential })
        }
    }

    @Test
    fun `rename, update and delete touch only the targeted account`() {
        val a = prefs.addAccount(AiService.OPENROUTER, "A", key("ka"))
        val b = prefs.addAccount(AiService.OPENROUTER, "B", key("kb"))

        prefs.updateAccountLabel(AiService.OPENROUTER, a.id, "Renamed")
        prefs.updateAccountCredential(AiService.OPENROUTER, b.id, key("kb2"))

        assertEquals(
            listOf(ServiceAccount(a.id, "Renamed", key("ka")), ServiceAccount(b.id, "B", key("kb2"))),
            prefs.getAccounts(AiService.OPENROUTER)
        )

        prefs.deleteAccount(AiService.OPENROUTER, a.id)

        assertEquals(listOf(b.id), prefs.getAccounts(AiService.OPENROUTER).map { it.id })
        prefs.deleteAccount(AiService.OPENROUTER, b.id)
        assertFalse(prefs.hasCredential(AiService.OPENROUTER))
        assertFalse(store.data.containsKey("OPENROUTER_accounts"))
    }

    @Test
    fun `accounts of different services do not mix`() {
        prefs.addAccount(AiService.COPILOT, "gh", key("gho"))
        prefs.addAccount(AiService.OPENAI, "oa", key("sk"))

        assertEquals("gho", prefs.getAccounts(AiService.COPILOT).single().credential.accessToken)
        assertEquals("sk", prefs.getAccounts(AiService.OPENAI).single().credential.accessToken)
        assertTrue(prefs.getAccounts(AiService.OPENROUTER).isEmpty())
    }

    @Test
    fun `deepseek and zai keep their historical storage format`() {
        prefs.addAccount(AiService.DEEPSEEK, "Main", Credential.DeepseekCredential("ds-key"))
        prefs.addAccount(AiService.ZAI, "Z", Credential.ZaiCredential("zai-key"))

        assertEquals("ds-key", prefs.getDeepseekAccounts().single().apiKey)
        assertEquals("Main", prefs.getDeepseekAccounts().single().label)
        assertEquals("zai-key", prefs.getZaiAccounts().single().apiKey)
        assertTrue(store.data["DEEPSEEK_accounts"].toString().contains("\"apiKey\":\"ds-key\""))
    }

    @Test
    fun `old deepseek accounts written before the generic api are read as accounts`() {
        store.data["DEEPSEEK_accounts"] = """[{"id":"d1","label":"Old","apiKey":"k1"},{"id":"d2","label":"","apiKey":"k2"}]"""

        val accounts = prefs.getAccounts(AiService.DEEPSEEK)

        assertEquals(listOf("d1", "d2"), accounts.map { it.id })
        assertEquals(Credential.DeepseekCredential("k1"), accounts[0].credential)
    }

    @Test
    fun `claude accounts keep their cached org id through the generic api`() {
        val account = prefs.addAccount(AiService.CLAUDE, "Web", Credential.ClaudeWebCredential("sk-ant-sid02-a"))
        prefs.saveClaudeOrgId(account.id, "org-1")

        prefs.updateAccountLabel(AiService.CLAUDE, account.id, "Renamed")

        assertEquals("org-1", prefs.getClaudeOrgId(account.id))
        assertEquals("Renamed", prefs.getAccounts(AiService.CLAUDE).single().label)
    }

    // --- single-credential shortcuts -----------------------------------------------------------------------

    @Test
    fun `saveCredential stands for the first account and creates it when missing`() {
        prefs.saveCredential(AiService.OPENAI, key("k1"))
        val id = prefs.getAccounts(AiService.OPENAI).single().id
        prefs.addAccount(AiService.OPENAI, "Other", key("k2"))

        prefs.saveCredential(AiService.OPENAI, key("k1-bis"))

        val accounts = prefs.getAccounts(AiService.OPENAI)
        assertEquals(2, accounts.size)
        assertEquals(id, accounts[0].id)
        assertEquals(key("k1-bis"), prefs.loadCredential(AiService.OPENAI))
        assertEquals(key("k2"), accounts[1].credential)
    }

    @Test
    fun `deleteCredential removes every account of the service only`() {
        prefs.addAccount(AiService.COPILOT, "a", key("c1"))
        prefs.addAccount(AiService.COPILOT, "b", key("c2"))
        prefs.addAccount(AiService.OPENAI, "x", key("o1"))

        prefs.deleteCredential(AiService.COPILOT)

        assertFalse(prefs.hasCredential(AiService.COPILOT))
        assertTrue(prefs.hasCredential(AiService.OPENAI))
    }

    @Test
    fun `an account whose stored secret is unreadable is ignored`() {
        store.data["CODEX_accounts"] = """[{"id":"x","type":"codex","accessToken":"at"}]"""

        assertTrue("codex needs a refresh token", prefs.getAccounts(AiService.CODEX).isEmpty())
    }
}
