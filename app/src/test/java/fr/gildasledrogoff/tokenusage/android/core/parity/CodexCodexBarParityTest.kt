package fr.gildasledrogoff.tokenusage.core.parity

import fr.gildasledrogoff.tokenusage.core.data.CodexRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport
import fr.gildasledrogoff.tokenusage.core.network.codex.CodexApiService
import fr.gildasledrogoff.tokenusage.core.network.codex.CodexDto
import fr.gildasledrogoff.tokenusage.core.network.codex.CodexTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.widget.WidgetPrefsManager
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
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

class CodexCodexBarParityTest {
    private lateinit var env: ParityServer
    private lateinit var repository: CodexRepositoryImpl

    @Before
    fun setUp() {
        env = ParityServer()
        val prefs = mock(EncryptedPrefsManager::class.java)
        `when`(prefs.getAccounts(AiService.CODEX)).thenReturn(
            listOf(ServiceAccount("codex-1", "", Credential.CodexCredential("access", "refresh", "account-a")))
        )
        repository = CodexRepositoryImpl(
            env.service(CodexApiService::class.java),
            env.service(CodexTokenRefreshService::class.java),
            prefs,
            mock(WidgetPrefsManager::class.java),
            mock(WidgetQuotaPublisher::class.java)
        )
    }

    @After
    fun tearDown() = env.close()

    private suspend fun fetch(json: String): Result<QuotaInfo, AppError> {
        env.enqueue(200, json)
        return repository.fetchQuota()
    }

    private suspend fun quota(json: String): QuotaInfo {
        val result = fetch(json)
        assertTrue("Succès attendu, obtenu : $result", result is Result.Success)
        return (result as Result.Success).value
    }

    private fun QuotaInfo.window(label: String) = windows.firstOrNull { it.label == label }

    @Test
    fun `maps usage windows`() = runTest {
        val q = quota(
            """
            {
              "rate_limit": {
                "primary_window": { "used_percent": 22, "reset_at": 1766948068, "limit_window_seconds": 18000 },
                "secondary_window": { "used_percent": 43, "reset_at": 1767407914, "limit_window_seconds": 604800 }
              }
            }
            """
        )
        assertEquals(listOf("5-Hour", "7-Day"), q.windows.map { it.label })
        assertEquals(0.22, q.windows[0].utilization, 1e-9)
        assertEquals(0.43, q.windows[1].utilization, 1e-9)
        assertEquals(1766948068L, q.windows[0].resetsAt!!.epochSecond)
        assertNotNull(q.windows[1].resetsAt)
    }

    @Test
    fun `prolite plan type does not fail mapping`() = runTest {
        val q = quota(
            """
            {
              "plan_type": "prolite",
              "rate_limit": { "primary_window": { "used_percent": 12, "reset_at": 1766948068, "limit_window_seconds": 18000 } }
            }
            """
        )
        assertEquals(0.12, q.window("5-Hour")!!.utilization, 1e-9)
        assertEquals("Prolite", q.tier)
    }

    @Test
    fun `free weekly only window is labelled weekly`() = runTest {
        val q = quota(
            """
            {
              "plan_type": "free",
              "rate_limit": {
                "primary_window": { "used_percent": 0, "reset_at": 1775468693, "limit_window_seconds": 604800 },
                "secondary_window": null
              }
            }
            """
        )
        assertEquals(listOf("7-Day"), q.windows.map { it.label })
        assertEquals(0.0, q.windows[0].utilization, 1e-9)
    }

    @Test
    fun `single session window`() = runTest {
        val q = quota(
            """
            {
              "rate_limit": {
                "primary_window": { "used_percent": 9, "reset_at": 1766948068, "limit_window_seconds": 18000 },
                "secondary_window": null
              }
            }
            """
        )
        assertEquals(listOf("5-Hour"), q.windows.map { it.label })
        assertEquals(0.09, q.windows[0].utilization, 1e-9)
    }

    @Test
    fun `unknown single window is preserved`() = runTest {
        val q = quota(
            """
            {
              "rate_limit": {
                "primary_window": { "used_percent": 17, "reset_at": 1766948068, "limit_window_seconds": 32400 },
                "secondary_window": null
              }
            }
            """
        )
        assertEquals(1, q.windows.size)
        assertEquals(0.17, q.windows[0].utilization, 1e-9)
        assertEquals("9h", q.windows[0].label)
    }

    @Test
    fun `unknown secondary only window is preserved`() = runTest {
        val q = quota(
            """
            {
              "rate_limit": {
                "primary_window": null,
                "secondary_window": { "used_percent": 17, "reset_at": 1766948068, "limit_window_seconds": 32400 }
              }
            }
            """
        )
        assertEquals(1, q.windows.size)
        assertEquals(0.17, q.windows[0].utilization, 1e-9)
    }

    @Test
    @Ignore("Divergence CodexBar : Android conserve l'ordre de l'API (hebdo avant la fenêtre courte) ; CodexBar remet la fenêtre courte en premier.")
    fun `reversed weekly and unknown windows are ordered short first`() = runTest {
        val q = quota(
            """
            {
              "rate_limit": {
                "primary_window": { "used_percent": 43, "reset_at": 1767407914, "limit_window_seconds": 604800 },
                "secondary_window": { "used_percent": 17, "reset_at": 1766948068, "limit_window_seconds": 32400 }
              }
            }
            """
        )
        assertEquals(listOf("9h", "7-Day"), q.windows.map { it.label })
        assertEquals(0.17, q.windows[0].utilization, 1e-9)
        assertEquals(0.43, q.windows[1].utilization, 1e-9)
    }

    @Test
    fun `no windows yields empty windows`() = runTest {
        val q = quota("""{ "rate_limit": { "primary_window": null, "secondary_window": null } }""")
        assertTrue(q.windows.isEmpty())
    }

    @Test
    fun `valid window survives malformed secondary window`() = runTest {
        val q = quota(
            """
            {
              "rate_limit": {
                "primary_window": { "used_percent": 18, "reset_at": 1766948068, "limit_window_seconds": 18000 },
                "secondary_window": { "used_percent": "bad", "reset_at": 1767407914, "limit_window_seconds": 604800 }
              }
            }
            """
        )
        assertEquals(0.18, q.window("5-Hour")!!.utilization, 1e-9)
        assertNull(q.window("7-Day"))
    }

    @Test
    fun `weekly window survives malformed primary window`() = runTest {
        val q = quota(
            """
            {
              "rate_limit": {
                "primary_window": { "used_percent": "bad", "reset_at": 1766948068, "limit_window_seconds": 18000 },
                "secondary_window": { "used_percent": 43, "reset_at": 1767407914, "limit_window_seconds": 604800 }
              }
            }
            """
        )
        assertNull(q.window("5-Hour"))
        assertEquals(0.43, q.window("7-Day")!!.utilization, 1e-9)
    }

    @Test
    fun `malformed credits payload keeps usage`() = runTest {
        val q = quota(
            """
            {
              "rate_limit": { "primary_window": { "used_percent": 22, "reset_at": 1766948068, "limit_window_seconds": 18000 } },
              "credits": { "has_credits": false, "unlimited": false, "balance": [] }
            }
            """
        )
        assertEquals(0.22, q.window("5-Hour")!!.utilization, 1e-9)
    }

    @Test
    fun `credits balance string is decoded`() {
        val response = DtoTestSupport.parse<CodexDto.UsageResponse>(
            """
            {
              "plan_type": "pro",
              "rate_limit": { "primary_window": { "used_percent": 12, "reset_at": 1766948068, "limit_window_seconds": 18000 } },
              "credits": { "has_credits": false, "unlimited": false, "balance": "0" }
            }
            """
        )
        assertEquals("pro", response.planType)
        assertEquals(0.0, CodexRepositoryImpl.parseBalance(response.credits?.balance)!!, 0.0)
        assertEquals(false, response.credits?.hasCredits)
        assertEquals(false, response.credits?.unlimited)
    }

    @Ignore("Divergence CodexBar : le solde de crédits Codex (credits.balance) est décodé mais jamais exposé dans QuotaInfo.")
    @Test
    fun `credits only payload exposes the credit balance`() = runTest {
        val q = quota(
            """
            {
              "rate_limit": { "primary_window": null, "secondary_window": null },
              "credits": { "has_credits": true, "unlimited": false, "balance": "14.5" }
            }
            """
        )
        assertEquals(14.5, q.accountBalances.single().balance!!.totalBalance, 1e-9)
    }

    @Ignore("Divergence CodexBar : `additional_rate_limits` (Codex Spark 5h / hebdo) n'est pas décodé par Android.")
    @Test
    fun `additional spark limit becomes extra windows`() = runTest {
        val q = quota(
            """
            {
              "plan_type": "pro",
              "rate_limit": {
                "primary_window": { "used_percent": 22, "reset_at": 1766948068, "limit_window_seconds": 18000 },
                "secondary_window": { "used_percent": 43, "reset_at": 1767407914, "limit_window_seconds": 604800 }
              },
              "additional_rate_limits": [
                {
                  "limit_name": "GPT-5.3-Codex-Spark",
                  "metered_feature": "gpt_5_3_codex_spark",
                  "rate_limit": {
                    "allowed": true, "limit_reached": false,
                    "primary_window": { "used_percent": 30, "reset_at": 1766948068, "limit_window_seconds": 18000, "reset_after_seconds": 12345 },
                    "secondary_window": { "used_percent": 100, "reset_at": 1767407914, "limit_window_seconds": 604800, "reset_after_seconds": 56789 }
                  }
                }
              ]
            }
            """
        )
        assertEquals(4, q.windows.size)
        assertEquals(0.30, q.windows[2].utilization, 1e-9)
        assertEquals(1.0, q.windows[3].utilization, 1e-9)
    }

    @Test
    fun `malformed additional limits keep primary usage`() = runTest {
        val q = quota(
            """
            {
              "rate_limit": { "primary_window": { "used_percent": 22, "reset_at": 1766948068, "limit_window_seconds": 18000 } },
              "additional_rate_limits": ["garbage", 1, true]
            }
            """
        )
        assertEquals(listOf("5-Hour"), q.windows.map { it.label })
        assertEquals(0.22, q.windows[0].utilization, 1e-9)
    }

    @Ignore("Divergence CodexBar : `rate_limit.individual_limit` (plafond mensuel de crédits) n'est pas décodé par Android.")
    @Test
    fun `monthly credit limit is exposed`() = runTest {
        val q = quota(
            """
            {
              "rate_limit": {
                "primary_window": null, "secondary_window": null,
                "individual_limit": { "limit": 100000, "used": 7761, "remaining_percent": 92.239, "resets_at": 1782864000 }
              },
              "credits": { "has_credits": true, "unlimited": false, "balance": "0" }
            }
            """
        )
        assertEquals(1, q.windows.size)
        assertEquals(0.07761, q.windows[0].utilization, 1e-9)
    }

    @Test
    fun `usage request sends bearer token and chatgpt account id`() = runTest {
        quota("""{ "rate_limit": { "primary_window": { "used_percent": 7, "limit_window_seconds": 18000 } } }""")
        val request = env.server.takeRequest()
        assertEquals("/backend-api/wham/usage", request.path)
        assertEquals("Bearer access", request.getHeader("Authorization"))
        assertEquals("account-a", request.getHeader("ChatGPT-Account-Id"))
    }

    @Test
    fun `401 then terminal refresh error is an auth failure`() = runTest {
        env.enqueue(401, "")
        env.enqueue(400, """{"error":"invalid_grant"}""")
        val result = repository.fetchQuota()
        assertTrue(result is Result.Failure && result.error is AppError.AuthError)
    }
}
