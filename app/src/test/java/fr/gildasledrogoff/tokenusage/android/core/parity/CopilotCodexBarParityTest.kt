package fr.gildasledrogoff.tokenusage.core.parity

import fr.gildasledrogoff.tokenusage.core.data.CopilotRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport
import fr.gildasledrogoff.tokenusage.core.network.copilot.CopilotApiService
import fr.gildasledrogoff.tokenusage.core.network.copilot.CopilotDto
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant

class CopilotCodexBarParityTest {
    private lateinit var env: ParityServer
    private lateinit var repository: CopilotRepositoryImpl

    @Before
    fun setUp() {
        env = ParityServer()
        val prefs = mock(EncryptedPrefsManager::class.java)
        `when`(prefs.getAccounts(AiService.COPILOT)).thenReturn(
            listOf(ServiceAccount("copilot-1", "", Credential.ApiKeyCredential("test-token-placeholder")))
        )
        repository = CopilotRepositoryImpl(env.service(CopilotApiService::class.java), prefs)
    }

    @After
    fun tearDown() = env.close()

    private fun map(json: String): Result<QuotaInfo, AppError> =
        CopilotRepositoryImpl.toQuotaInfo(DtoTestSupport.parse<CopilotDto.UserResponse>(json))

    private fun quota(json: String): QuotaInfo {
        val result = map(json)
        assertTrue("Succès attendu, obtenu : $result", result is Result.Success)
        return (result as Result.Success).value
    }

    private fun QuotaInfo.window(label: String) = windows.firstOrNull { it.label == label }

    @Test
    fun `ended subscription is reported`() {
        val result = map(
            """{"login":"fixture-user","access_type_sku":"subscription_ended","copilot_plan":"individual",
             "chat_enabled":false,"cli_enabled":false,"can_signup_for_limited":true}"""
        )
        assertTrue(result is Result.Failure)
        assertEquals("Copilot subscription ended", ((result as Result.Failure).error as AppError.ParseError).message)
    }

    @Test
    fun `unknown quota-less response is a failure`() {
        val result = map("""{"copilot_plan":"individual","access_type_sku":"future-entitlement"}""")
        assertTrue(result is Result.Failure && result.error is AppError.ParseError)
    }

    @Test
    fun `business token billing placeholders give no windows but succeed`() = runTest {
        env.enqueue(
            200,
            """
            {
              "copilot_plan": "business",
              "token_based_billing": true,
              "quota_reset_date": "2026-09-01",
              "quota_snapshots": {
                "premium_interactions": { "entitlement": 0, "remaining": 0, "percent_remaining": 100, "quota_id": "premium_interactions", "credits_used": 31 },
                "chat": { "entitlement": 0, "remaining": 0, "percent_remaining": 100, "quota_id": "chat", "credits_used": 0 }
              }
            }
            """
        )
        val q = (repository.fetchQuota() as Result.Success).value
        assertTrue(q.windows.isEmpty())
        assertEquals("Business", q.tier)
        val request = env.server.takeRequest()
        assertEquals("/copilot_internal/user", request.path)
        assertEquals("token test-token-placeholder", request.getHeader("Authorization"))
    }

    @Test
    fun `unlimited only chat quota does not fail`() {
        val q = quota(
            """
            {
              "copilot_plan": "individual",
              "quota_reset_date": "2026-07-01",
              "quota_snapshots": { "chat_messages": { "entitlement": 0, "remaining": 0, "quota_id": "chat_messages", "unlimited": true } }
            }
            """
        )
        assertTrue(q.windows.isEmpty())
        assertEquals("Individual", q.tier)
    }

    @Test
    fun `finite premium kept and unlimited chat omitted`() {
        val q = quota(
            """
            {
              "copilot_plan": "individual",
              "quota_reset_date": "2026-08-01T00:00:00Z",
              "quota_snapshots": {
                "premium_interactions": { "entitlement": 200, "remaining": 156.2, "percent_remaining": 78.1, "quota_id": "premium_interactions" },
                "chat_messages": { "entitlement": 0, "remaining": 0, "quota_id": "chat_messages", "unlimited": true }
              }
            }
            """
        )
        assertEquals(1, q.windows.size)
        assertEquals(0.219, q.window("Premium requests")!!.utilization, 1e-4)
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), q.window("Premium requests")!!.resetsAt)
    }

    @Test
    fun `finite monthly chat quota replaces unlimited direct chat`() {
        val q = quota(
            """
            {
              "copilot_plan": "individual",
              "quota_snapshots": { "chat_messages": { "entitlement": 0, "remaining": 0, "quota_id": "chat_messages", "unlimited": true } },
              "monthly_quotas": { "chat": 100 },
              "limited_user_quotas": { "chat": 60 }
            }
            """
        )
        assertNull(q.window("Premium requests"))
        assertEquals(0.40, q.window("Chat")!!.utilization, 1e-9)
    }

    @Test
    fun `reset date is attached to every window`() {
        val q = quota(
            """
            {
              "copilot_plan": "individual",
              "quota_reset_date": "2026-07-01",
              "quota_snapshots": {
                "premium_interactions": { "entitlement": 500, "remaining": 125, "percent_remaining": 25, "quota_id": "premium_interactions" },
                "chat": { "entitlement": 300, "remaining": 240, "percent_remaining": 80, "quota_id": "chat" }
              }
            }
            """
        )
        val reset = Instant.parse("2026-07-01T00:00:00Z")
        assertEquals(0.75, q.window("Premium requests")!!.utilization, 1e-9)
        assertEquals(reset, q.window("Premium requests")!!.resetsAt)
        assertEquals(0.20, q.window("Chat")!!.utilization, 1e-9)
        assertEquals(reset, q.window("Chat")!!.resetsAt)
    }

    @Test
    fun `parse quota reset date`() {
        assertEquals(Instant.parse("2026-07-01T00:00:00Z"), CopilotRepositoryImpl.parseResetDate("2026-07-01"))
        assertEquals(Instant.parse("2026-07-01T08:30:45Z"), CopilotRepositoryImpl.parseResetDate("2026-07-01T08:30:45Z"))
        assertEquals(
            Instant.parse("2026-07-01T08:30:45.123Z"),
            CopilotRepositoryImpl.parseResetDate("2026-07-01T08:30:45.123Z")
        )
        assertNull(CopilotRepositoryImpl.parseResetDate(" "))
    }

    @Test
    fun `decodes quota snapshots payload`() {
        val q = quota(
            """
            {
              "copilot_plan": "free",
              "assigned_date": "2025-01-01",
              "quota_reset_date": "2025-02-01",
              "quota_snapshots": {
                "premium_interactions": { "entitlement": 500, "remaining": 450, "percent_remaining": 90, "quota_id": "premium_interactions" },
                "chat": { "entitlement": 300, "remaining": 150, "percent_remaining": 50, "quota_id": "chat" }
              }
            }
            """
        )
        assertEquals("Free", q.tier)
        assertEquals(0.10, q.window("Premium requests")!!.utilization, 1e-9)
        assertEquals(0.50, q.window("Chat")!!.utilization, 1e-9)
    }

    @Test
    fun `chat only payload`() {
        val q = quota(
            """
            {
              "copilot_plan": "free",
              "quota_snapshots": { "chat": { "entitlement": 200, "remaining": 75, "percent_remaining": 37.5, "quota_id": "chat" } }
            }
            """
        )
        assertNull(q.window("Premium requests"))
        assertEquals(0.625, q.window("Chat")!!.utilization, 1e-9)
    }

    @Test
    fun `monthly and limited quota payload with string numbers`() {
        val q = quota(
            """
            {
              "copilot_plan": "free",
              "monthly_quotas": { "chat": "500", "completions": 300 },
              "limited_user_quotas": { "chat": 125, "completions": "75" }
            }
            """
        )
        assertEquals(0.75, q.window("Completions")!!.utilization, 1e-9)
        assertEquals(0.75, q.window("Chat")!!.utilization, 1e-9)
    }

    @Test
    fun `unknown snapshot key is used as chat fallback`() {
        val q = quota(
            """
            {
              "copilot_plan": "free",
              "quota_snapshots": { "mystery_bucket": { "entitlement": 100, "remaining": 40, "percent_remaining": 40, "quota_id": "mystery_bucket" } }
            }
            """
        )
        assertNull(q.window("Premium requests"))
        assertEquals(0.60, q.window("Chat")!!.utilization, 1e-9)
    }

    @Test
    fun `placeholder known snapshot is ignored for unknown key fallback`() {
        val q = quota(
            """
            {
              "copilot_plan": "free",
              "quota_snapshots": {
                "premium_interactions": {},
                "mystery_bucket": { "entitlement": 100, "remaining": 40, "percent_remaining": 40, "quota_id": "mystery_bucket" }
              }
            }
            """
        )
        assertNull(q.window("Premium requests"))
        assertEquals(0.60, q.window("Chat")!!.utilization, 1e-9)
    }

    @Test
    fun `percent derived from entitlement and remaining`() {
        val q = quota(
            """{"copilot_plan":"free","quota_snapshots":{"chat":{"entitlement":120,"remaining":30,"quota_id":"chat"}}}"""
        )
        assertEquals(0.75, q.window("Chat")!!.utilization, 1e-9)
    }

    @Test
    fun `over quota percent remaining is preserved`() {
        val q = quota(
            """
            {
              "copilot_plan": "paid",
              "quota_snapshots": { "premium_interactions": { "entitlement": 500, "remaining": -75, "percent_remaining": -15, "quota_id": "premium_interactions" } }
            }
            """
        )
        assertEquals(1.15, q.window("Premium requests")!!.utilization, 1e-9)
    }

    @Test
    fun `over quota derived from negative remaining`() {
        val q = quota(
            """{"copilot_plan":"paid","quota_snapshots":{"chat":{"entitlement":500,"remaining":-75,"quota_id":"chat"}}}"""
        )
        assertEquals(1.15, q.window("Chat")!!.utilization, 1e-9)
    }

    @Test
    fun `underdetermined snapshot gives no window`() {
        val result = map("""{"copilot_plan":"free","quota_snapshots":{"chat":{"remaining":30,"quota_id":"chat"}}}""")
        val windows = (result as? Result.Success)?.value?.windows.orEmpty()
        assertTrue(windows.none { it.label == "Chat" })
    }

    @Test
    fun `zero entitlement quotas are unavailable`() {
        val q = quota(
            """
            {
              "copilot_plan": "business",
              "token_based_billing": true,
              "quota_snapshots": {
                "premium_interactions": { "entitlement": 0, "remaining": 0, "percent_remaining": 100, "quota_id": "premium_interactions" },
                "chat": { "entitlement": 0, "remaining": 0, "percent_remaining": 100, "quota_id": "chat" },
                "completions": { "entitlement": 0, "remaining": 0, "percent_remaining": 100, "quota_id": "completions" }
              }
            }
            """
        )
        assertTrue(q.windows.isEmpty())
    }

    @Test
    fun `premium kept and unlimited chat has no window`() {
        val q = quota(
            """
            {
              "copilot_plan": "individual",
              "quota_snapshots": {
                "premium_interactions": { "entitlement": 200, "remaining": 191, "percent_remaining": 95.5, "quota_id": "premium_interactions" },
                "chat_messages": { "entitlement": 0, "remaining": 0, "quota_id": "chat_messages", "unlimited": true }
              }
            }
            """
        )
        assertEquals(0.045, q.window("Premium requests")!!.utilization, 1e-9)
        assertNull(q.window("Chat"))
    }

    @Test
    fun `unlimited overrides placeholder percent`() {
        val q = quota(
            """
            {
              "copilot_plan": "individual",
              "quota_snapshots": { "chat": { "entitlement": 0, "remaining": 0, "percent_remaining": 0, "quota_id": "chat", "unlimited": true } }
            }
            """
        )
        assertTrue(q.windows.isEmpty())
    }

    @Test
    fun `fully consumed quota is kept`() {
        val q = quota(
            """{"copilot_plan":"free","quota_snapshots":{"chat":{"entitlement":100,"remaining":0,"percent_remaining":0,"quota_id":"chat"}}}"""
        )
        assertEquals(1.0, q.window("Chat")!!.utilization, 1e-9)
    }

    @Test
    fun `percent only snapshot is available`() {
        val q = quota(
            """{"copilot_plan":"free","quota_snapshots":{"chat":{"percent_remaining":40,"quota_id":"chat"}}}"""
        )
        assertEquals(0.60, q.window("Chat")!!.utilization, 1e-9)
    }

    @Ignore("Divergence CodexBar : le compteur `credits_used` (facturation par jetons) et les crédits de siège ne sont pas exposés par Android.")
    @Test
    fun `token billed credits counter is exposed`() {
        val q = quota(
            """
            {
              "copilot_plan": "business", "token_based_billing": true,
              "quota_snapshots": { "premium_interactions": { "entitlement": 0, "remaining": 0, "percent_remaining": 100, "quota_id": "premium_interactions", "credits_used": 31 } }
            }
            """
        )
        assertNotNull(q.spending.firstOrNull())
    }
}
