package fr.gildasledrogoff.tokenusage.core.parity

import fr.gildasledrogoff.tokenusage.core.data.ClaudeRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeApiService
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeWebApiService
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

class ClaudeCodexBarParityTest {
    private lateinit var env: ParityServer
    private lateinit var prefs: EncryptedPrefsManager
    private lateinit var repository: ClaudeRepositoryImpl

    @Before
    fun setUp() {
        env = ParityServer()
        prefs = mock(EncryptedPrefsManager::class.java)
        repository = ClaudeRepositoryImpl(
            env.service(ClaudeApiService::class.java),
            env.service(ClaudeWebApiService::class.java),
            env.service(ClaudeTokenRefreshService::class.java),
            prefs
        )
        useOAuth()
    }

    @After
    fun tearDown() = env.close()

    private fun useOAuth() {
        `when`(prefs.getAccounts(AiService.CLAUDE)).thenReturn(
            listOf(ServiceAccount(ACCOUNT, "", Credential.ClaudeCredential("oauth-token", "refresh")))
        )
    }

    private fun useWeb() {
        `when`(prefs.getAccounts(AiService.CLAUDE)).thenReturn(
            listOf(ServiceAccount(ACCOUNT, "", Credential.ClaudeWebCredential("sk-ant-sid01-test-session-key")))
        )
    }

    private suspend fun mapOAuth(json: String): Result<QuotaInfo, AppError> {
        env.enqueue(200, json)
        return repository.fetchQuota()
    }

    private suspend fun quota(json: String): QuotaInfo {
        val result = mapOAuth(json)
        assertTrue("Succès attendu, obtenu : $result", result is Result.Success)
        return (result as Result.Success).value
    }

    private fun QuotaInfo.window(label: String) = windows.firstOrNull { it.label == label }

    @Test
    fun `maps oauth usage to snapshot`() = runTest {
        val q = quota(
            """
            {
              "five_hour": { "utilization": 12.5, "resets_at": "2025-12-25T12:00:00.000Z" },
              "seven_day": { "utilization": 30, "resets_at": "2025-12-31T00:00:00.000Z" },
              "seven_day_sonnet": { "utilization": 5 }
            }
            """
        )
        assertEquals(0.125, q.window("5-Hour")!!.utilization, 1e-9)
        assertNotNull(q.window("5-Hour")!!.resetsAt)
        assertEquals(0.30, q.window("7-Day")!!.utilization, 1e-9)
        assertEquals(0.05, q.window("Sonnet")!!.utilization, 1e-9)
    }

    @Test
    fun `opus window is exposed when sonnet is missing`() = runTest {
        val q = quota(
            """
            {
              "five_hour": { "utilization": 10, "resets_at": "2025-12-25T12:00:00.000Z" },
              "seven_day_opus": { "utilization": 42 }
            }
            """
        )
        assertEquals(0.42, q.window("Opus")!!.utilization, 1e-9)
    }

    @Test
    fun `fable scoped weekly limit from limits array`() = runTest {
        val q = quota(
            """
            {
              "five_hour": { "utilization": 11.0, "resets_at": "2026-07-03T00:30:00.282668+00:00" },
              "seven_day": { "utilization": 9.0, "resets_at": "2026-07-08T09:00:00.282694+00:00" },
              "seven_day_opus": null,
              "seven_day_sonnet": null,
              "limits": [
                {
                  "kind": "session", "group": "session", "percent": 11,
                  "resets_at": "2026-07-03T00:30:00.282668+00:00", "scope": null, "is_active": true
                },
                {
                  "kind": "weekly_all", "group": "weekly", "percent": 9,
                  "resets_at": "2026-07-08T09:00:00.282694+00:00", "scope": null, "is_active": false
                },
                {
                  "kind": "weekly_scoped", "group": "weekly", "percent": 5,
                  "resets_at": "2026-07-08T09:00:00.283070+00:00",
                  "scope": { "model": { "id": null, "display_name": "Fable" }, "surface": null },
                  "is_active": false
                }
              ]
            }
            """
        )
        val fable = q.window("Fable")!!
        assertEquals(0.05, fable.utilization, 1e-9)
        assertNotNull(fable.resetsAt)
        assertEquals(1, q.windows.count { it.label == "5-Hour" })
        assertEquals(1, q.windows.count { it.label == "7-Day" })
    }

    @Test
    fun `fable limit survives malformed sibling entries`() = runTest {
        val q = quota(
            """
            {
              "five_hour": { "utilization": 11.0, "resets_at": "2026-07-03T00:30:00.282668+00:00" },
              "seven_day": { "utilization": 9.0, "resets_at": "2026-07-08T09:00:00.282694+00:00" },
              "limits": [
                null,
                { "kind": "session", "group": "session", "percent": "11", "resets_at": null, "scope": null },
                {
                  "kind": "weekly_scoped", "group": "weekly", "percent": 5, "resets_at": 1791800000,
                  "scope": { "model": { "id": null, "display_name": "Fable" }, "surface": null },
                  "is_active": false
                }
              ]
            }
            """
        )
        val fable = q.window("Fable")!!
        assertEquals(0.05, fable.utilization, 1e-9)
        assertNull(fable.resetsAt)
    }

    @Test
    @Ignore("Divergence CodexBar : Android expose volontairement « Weekly scoped » (cf. ClaudeRepositoryImplTest) ; CodexBar l'ignore.")
    fun `weekly scoped limit without model name is not surfaced as a model window`() = runTest {
        val q = quota(
            """
            {
              "five_hour": { "utilization": 11.0, "resets_at": "2026-07-03T00:30:00.282668+00:00" },
              "limits": [
                {
                  "kind": "weekly_scoped", "group": "weekly", "percent": 5,
                  "resets_at": "2026-07-08T09:00:00.283070+00:00",
                  "scope": { "model": null, "surface": null }, "is_active": false
                }
              ]
            }
            """
        )
        assertNull(q.window("Weekly scoped"))
        assertEquals(1, q.windows.size)
    }

    @Test
    fun `boolean scoped percentages do not become windows`() = runTest {
        val q = quota(
            """{"five_hour":{"utilization":12},"limits":[
              {"kind":"weekly_scoped","group":"weekly","percent":true,"scope":{"model":{"display_name":"Fable"}}},
              {"kind":"weekly_scoped","group":"weekly","percent":false,"scope":{"model":{"display_name":"Story"}}}
            ]}"""
        )
        assertNull(q.window("Fable"))
        assertNull(q.window("Story"))
    }

    @Test
    @Ignore("Divergence CodexBar : idem : limite weekly_scoped sans nom de modèle exposée par Android.")
    fun `nan and blank named scoped limits are ignored`() = runTest {
        val q = quota(
            """
            {
              "five_hour": { "utilization": 12 },
              "limits": [
                { "kind": "weekly_scoped", "group": "weekly", "percent": 5,
                  "scope": { "model": { "display_name": "   " } } }
              ]
            }
            """
        )
        assertEquals(listOf("5-Hour"), q.windows.map { it.label })
    }

    @Test
    fun `multiple scoped weekly windows keep payload order`() = runTest {
        useWeb()
        env.enqueue(200, """[{"uuid":"org-1","name":"Org","capabilities":["chat"]}]""")
        env.enqueue(
            200,
            """
            {
              "five_hour": { "utilization": 9, "resets_at": "2026-07-03T00:30:00.440902+00:00" },
              "seven_day": { "utilization": 20, "resets_at": "2026-07-08T09:00:00.440924+00:00" },
              "limits": [
                {
                  "kind": "weekly_scoped", "group": "weekly", "percent": 12,
                  "resets_at": "2026-07-08T09:00:00.441154+00:00",
                  "scope": { "model": { "id": null, "display_name": "Opus" }, "surface": null },
                  "is_active": false
                },
                {
                  "kind": "weekly_scoped", "group": "weekly", "percent": 29,
                  "resets_at": "2026-07-08T09:00:00.441154+00:00",
                  "scope": { "model": { "id": null, "display_name": "Fable" }, "surface": null },
                  "is_active": false
                }
              ]
            }
            """
        )
        val q = (repository.fetchQuota() as Result.Success).value
        assertEquals(listOf("5-Hour", "7-Day", "Opus", "Fable"), q.windows.map { it.label })
    }

    @Test
    fun `web usage response with and without weekly`() = runTest {
        useWeb()
        env.enqueue(200, """[{"uuid":"org-1","capabilities":["chat"]}]""")
        env.enqueue(
            200,
            """
            {
              "five_hour": { "utilization": 9, "resets_at": "2025-12-23T16:00:00.000Z" },
              "seven_day": { "utilization": 4, "resets_at": "2025-12-29T23:00:00.000Z" },
              "seven_day_opus": { "utilization": 1 }
            }
            """
        )
        val q = (repository.fetchQuota() as Result.Success).value
        assertEquals(0.09, q.window("5-Hour")!!.utilization, 1e-9)
        assertEquals(0.04, q.window("7-Day")!!.utilization, 1e-9)
        assertEquals(0.01, q.window("Opus")!!.utilization, 1e-9)
    }

    @Test
    fun `web organizations prefers hybrid chat org over api only`() = runTest {
        useWeb()
        env.enqueue(
            200,
            """
            [
              { "uuid": "org-api", "name": "API Org", "capabilities": ["api"] },
              { "uuid": "org-hybrid", "name": "Hybrid Org", "capabilities": ["api", "chat"] }
            ]
            """
        )
        env.enqueue(200, """{"five_hour":{"utilization":1}}""")
        repository.fetchQuota()
        env.server.takeRequest()
        assertEquals("/api/organizations/org-hybrid/usage", env.server.takeRequest().path)
    }

    @Test
    fun `five hour absent keeps session absent`() = runTest {
        val q = quota(
            """
            {
              "seven_day": { "utilization": 42, "resets_at": "2025-12-29T23:00:00.000Z" },
              "seven_day_sonnet": { "utilization": 17, "resets_at": "2025-12-29T23:00:00.000Z" }
            }
            """
        )
        assertNull(q.window("5-Hour"))
        assertEquals(0.42, q.window("7-Day")!!.utilization, 1e-9)
        assertEquals(0.17, q.window("Sonnet")!!.utilization, 1e-9)
    }

    @Test
    fun `five hour without utilization is not shown as zero percent`() = runTest {
        val q = quota(
            """
            {
              "five_hour": { "resets_at": "2025-12-23T16:00:00.000Z" },
              "seven_day": { "utilization": 9, "resets_at": "2025-12-29T23:00:00.000Z" }
            }
            """
        )
        assertNull(q.window("5-Hour"))
        assertEquals(0.09, q.window("7-Day")!!.utilization, 1e-9)
    }

    @Test
    fun `weekly only max plan`() = runTest {
        val q = quota(
            """
            {
              "five_hour": null,
              "seven_day": { "utilization": 0.0, "resets_at": "2026-11-09T16:59:59.902585+00:00" },
              "seven_day_oauth_apps": null,
              "seven_day_opus": null,
              "seven_day_sonnet": null,
              "extra_usage": { "is_enabled": false, "monthly_limit": null, "used_credits": null },
              "limits": [
                { "kind": "weekly_all", "group": "weekly", "percent": 0,
                  "resets_at": "2026-11-09T16:59:59.902585+00:00", "scope": null, "is_active": true }
              ]
            }
            """
        )
        assertNull(q.window("5-Hour"))
        assertEquals(0.0, q.window("7-Day")!!.utilization, 1e-9)
        assertNull(q.extraUsage)
    }

    @Test
    fun `empty object is a parse error`() = runTest {
        val result = mapOAuth("{}")
        assertTrue(result is Result.Failure && result.error is AppError.ParseError)
    }

    @Test
    fun `extra usage is skipped when disabled`() = runTest {
        val q = quota(
            """
            {
              "five_hour": { "utilization": 1, "resets_at": "2025-12-25T12:00:00.000Z" },
              "extra_usage": { "is_enabled": false, "monthly_limit": 100, "used_credits": 10 }
            }
            """
        )
        assertNull(q.extraUsage)
    }

    @Test
    fun `extra usage minor units are converted to major units`() = runTest {
        val q = quota(
            """
            {
              "five_hour": { "utilization": 1, "resets_at": "2025-12-25T12:00:00.000Z" },
              "extra_usage": { "is_enabled": true, "monthly_limit": 2050, "used_credits": 325 }
            }
            """
        )
        val extra = q.extraUsage!!
        assertEquals("USD", extra.currency)
        assertEquals(20.5, extra.monthlyLimit, 1e-9)
        assertEquals(3.25, extra.usedCredits, 1e-9)
    }

    @Test
    fun `extra usage 2000 and 520 cents`() = runTest {
        val q = quota(
            """
            {
              "five_hour": { "utilization": 1, "resets_at": "2025-12-25T12:00:00.000Z" },
              "extra_usage": { "is_enabled": true, "monthly_limit": 2000, "used_credits": 520, "currency": "USD" }
            }
            """
        )
        assertEquals(20.0, q.extraUsage!!.monthlyLimit, 1e-9)
        assertEquals(5.2, q.extraUsage.usedCredits, 1e-9)
    }

    @Test
    fun `high extra usage limit`() = runTest {
        val q = quota(
            """
            {
              "five_hour": { "utilization": 1, "resets_at": "2025-12-25T12:00:00.000Z" },
              "extra_usage": { "is_enabled": true, "monthly_limit": 200000, "used_credits": 22200, "currency": "USD" }
            }
            """
        )
        assertEquals(2000.0, q.extraUsage!!.monthlyLimit, 1e-9)
        assertEquals(222.0, q.extraUsage.usedCredits, 1e-9)
    }

    @Test
    fun `enterprise spend limit only payload is a success in major units`() = runTest {
        val q = quota(
            """
            {
              "extra_usage": {
                "is_enabled": true, "monthly_limit": 2000, "used_credits": 763,
                "utilization": 38.15, "currency": "EUR"
              }
            }
            """
        )
        val extra = q.extraUsage!!
        assertEquals("EUR", extra.currency)
        assertEquals(20.0, extra.monthlyLimit, 1e-9)
        assertEquals(7.63, extra.usedCredits, 1e-9)
        assertEquals(38.15, extra.utilization, 1e-9)
    }

    @Test
    fun `large enterprise spend limit`() = runTest {
        val q = quota(
            """
            {
              "extra_usage": {
                "is_enabled": true, "monthly_limit": 1000000, "used_credits": 123456,
                "utilization": 12.3456, "currency": "USD"
              }
            }
            """
        )
        assertEquals(10000.0, q.extraUsage!!.monthlyLimit, 1e-6)
        assertEquals(1234.56, q.extraUsage.usedCredits, 1e-6)
    }

    @Test
    fun `429 is rate limited and issues a single request`() = runTest {
        env.enqueue(429, """{"error":"Missing required scope user:profile"}""", mapOf("Retry-After" to "300"))
        val result = repository.fetchQuota()
        assertTrue(result is Result.Failure && result.error is AppError.RateLimited)
        assertEquals(1, env.server.requestCount)
    }

    @Test
    fun `usage request carries bearer and oauth beta header`() = runTest {
        quota("""{"five_hour":{"utilization":12}}""")
        val request = env.server.takeRequest()
        assertEquals("/api/oauth/usage", request.path)
        assertEquals("Bearer oauth-token", request.getHeader("Authorization"))
        assertEquals("oauth-2025-04-20", request.getHeader("anthropic-beta"))
    }

    @Test
    fun `omelette promotional window is kept when present and optional when absent`() = runTest {
        val with = quota(
            """{"five_hour":{"utilization":10},"omelette_promotional":{"utilization":25,"resets_at":"2025-12-31T00:00:00.000Z"}}"""
        )
        assertEquals(0.25, with.window("Claude Design (Promo)")!!.utilization, 1e-9)
        val without = quota("""{"five_hour":{"utilization":10},"omelette_promotional":null}""")
        assertNull(without.window("Claude Design (Promo)"))
        assertEquals(1, without.windows.size)
    }

    private companion object {
        const val ACCOUNT = "claude-parity-1"
    }
}
