package fr.gildasledrogoff.tokenusage.core.parity

import fr.gildasledrogoff.tokenusage.core.data.OpenRouterRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import fr.gildasledrogoff.tokenusage.core.network.openrouter.OpenRouterApiService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class OpenRouterCodexBarParityTest {
    private lateinit var env: ParityServer
    private lateinit var repository: OpenRouterRepositoryImpl
    private val requests = mutableListOf<RecordedRequest>()

    private val defaultCredits = """{"data":{"total_credits":5,"total_usage":3.10}}"""

    @Before
    fun setUp() {
        env = ParityServer()
        val prefs = mock(EncryptedPrefsManager::class.java)
        `when`(prefs.getAccounts(AiService.OPENROUTER)).thenReturn(
            listOf(ServiceAccount("or-1", "", Credential.ApiKeyCredential("fixture-key")))
        )
        repository = OpenRouterRepositoryImpl(env.service(OpenRouterApiService::class.java), prefs)
    }

    @After
    fun tearDown() = env.close()

    private fun route(
        key: Pair<Int, String>,
        credits: Pair<Int, String> = 200 to defaultCredits
    ) {
        env.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val (code, body) = when (request.path) {
                    "/key" -> key
                    "/credits" -> credits
                    else -> 404 to ""
                }
                return MockResponse().setResponseCode(code).setBody(body)
            }
        }
    }

    private suspend fun quota(
        keyBody: String,
        keyStatus: Int = 200,
        creditsBody: String = defaultCredits,
        creditsStatus: Int = 200
    ): QuotaInfo {
        route(keyStatus to keyBody, creditsStatus to creditsBody)
        val result = repository.fetchQuota()
        assertTrue("Succès attendu, obtenu : $result", result is Result.Success)
        return (result as Result.Success).value
    }

    private val QuotaInfo.keyWindow get() = windows.firstOrNull { it.label == "API key limit" }

    @Test
    fun `key caps remain independent of account balance`() = runTest {
        data class Case(val body: String, val used: Double?)
        val cases = listOf(
            Case("""{"data":{"limit":30,"limit_remaining":30,"limit_reset":"monthly","usage":0,"usage_monthly":0}}""", 0.0),
            Case("""{"data":{"limit":1,"limit_remaining":1}}""", 0.0),
            Case("""{"data":{"limit":1.9,"limit_remaining":1.9}}""", 0.0),
            Case("""{"data":{"limit":30,"limit_remaining":-5}}""", 1.0),
            Case("""{"data":{"limit":30}}""", null),
            Case("""{"data":{}}""", null),
            Case("""{"data":{"limit":null}}""", null),
            Case("""{"data":{"limit":0,"usage":0}}""", null),
            Case("""{"data":{"limit":-1,"usage":0}}""", null)
        )
        for (case in cases) {
            val q = quota(case.body)
            assertEquals(case.body, case.used, q.keyWindow?.utilization)
            assertEquals(case.body, 1.9, q.accountBalances.single().balance!!.totalBalance, 1e-9)
        }
    }

    @Test
    fun `reset window precedence and server remaining`() = runTest {
        for ((reset, used) in listOf("daily" to 0.20, "weekly" to 0.40, "monthly" to 0.60, "unknown" to 0.90)) {
            val body = """{"data":{"limit":30,"limit_reset":"$reset","usage":27,"usage_daily":6,"usage_weekly":12,"usage_monthly":18}}"""
            assertEquals(reset, used, quota(body).keyWindow!!.utilization, 1e-9)
            val server = """{"data":{"limit":30,"limit_remaining":30,"limit_reset":"$reset","usage":27,"usage_daily":6,"usage_weekly":12,"usage_monthly":18}}"""
            assertEquals(reset, 0.0, quota(server).keyWindow!!.utilization, 1e-9)
        }
    }

    @Test
    fun `key quota golden`() = runTest {
        val q = quota("""{"data":{"limit":20,"usage":5}}""")
        assertEquals(0.25, q.keyWindow!!.utilization, 1e-9)
        assertNull(q.keyWindow!!.resetsAt)
        assertEquals("Bearer fixture-key", requests.first().getHeader("Authorization"))
    }

    @Test
    fun `server remaining drives monthly quota`() = runTest {
        val q = quota(
            """{"data":{"limit":500,"limit_remaining":454.542594979,"limit_reset":"monthly","usage":433.286754736,
            "usage_daily":3.404645509,"usage_weekly":3.404645509,"usage_monthly":45.457405021}}"""
        )
        assertEquals(0.090914810042, q.keyWindow!!.utilization, 1e-9)
    }

    @Test
    fun `missing remaining falls back to reset window usage`() = runTest {
        val q = quota("""{"data":{"limit":500,"limit_reset":"monthly","usage":433.286754736,"usage_monthly":45.457405021}}""")
        assertEquals(0.090914810042, q.keyWindow!!.utilization, 1e-9)
    }

    @Test
    fun `reset window works without cumulative usage`() = runTest {
        val q = quota("""{"data":{"limit":500,"limit_reset":"monthly","usage_monthly":45.457405021}}""")
        assertEquals(0.090914810042, q.keyWindow!!.utilization, 1e-9)
    }

    @Test
    fun `negative server remaining is exhausted`() = runTest {
        val q = quota(
            """{"data":{"limit":500,"limit_remaining":-5,"limit_reset":"monthly","usage":433.286754736,"usage_monthly":45.457405021}}"""
        )
        assertEquals(1.0, q.keyWindow!!.utilization, 1e-9)
    }

    @Test
    fun `missing key limit has no window`() = runTest {
        assertNull(quota("""{"data":{}}""").keyWindow)
    }

    @Test
    fun `key failure keeps the credit balance`() = runTest {
        val q = quota("{}", keyStatus = 500)
        assertNull(q.keyWindow)
        assertEquals(1.9, q.accountBalances.single().balance!!.totalBalance, 1e-9)
    }

    @Test
    fun `malformed key limit degrades to unavailable`() = runTest {
        val q = quota("""{"data":{"limit":"twenty"}}""")
        assertNull(q.keyWindow)
        assertEquals(1.9, q.accountBalances.single().balance!!.totalBalance, 1e-9)
    }

    @Test
    fun `credits 401 preserves key quota`() = runTest {
        val q = quota(
            """{"data":{"limit":20,"usage":5}}""",
            creditsBody = """{"error":"bad token sk-or-v1-abc123","token":"secret-token"}""",
            creditsStatus = 401
        )
        assertEquals(0.25, q.keyWindow!!.utilization, 1e-9)
        assertTrue(q.accountBalances.isEmpty())
    }

    @Test
    fun `invalid credits payloads preserve key quota`() = runTest {
        for (credits in listOf("""{"data":{"total_credits":"many","total_usage":40}}""", "not-json")) {
            val q = quota("""{"data":{"limit":20,"usage":5}}""", creditsBody = credits)
            assertEquals(credits, 0.25, q.keyWindow!!.utilization, 1e-9)
            assertTrue(credits, q.accountBalances.isEmpty())
        }
    }

    @Test
    fun `credits 403 keeps spending breakdown`() = runTest {
        val q = quota(
            """{"data":{"limit":20,"usage":0.5,"usage_daily":0.12,"usage_weekly":0.74,"usage_monthly":4.56,
            "rate_limit":{"requests":120,"interval":"10s"}}}""",
            creditsStatus = 403
        )
        assertEquals(0.12, q.spending.first { it.period == SpendPeriod.TODAY }.amount, 1e-9)
        assertEquals(0.74, q.spending.first { it.period == SpendPeriod.THIS_WEEK }.amount, 1e-9)
        assertEquals(4.56, q.spending.first { it.period == SpendPeriod.THIS_MONTH }.amount, 1e-9)
    }

    @Test
    fun `credits remain visible when accepted`() = runTest {
        val q = quota(
            """{"data":{"limit":20,"usage":5}}""",
            creditsBody = """{"data":{"total_credits":100,"total_usage":40}}"""
        )
        assertEquals(60.0, q.accountBalances.single().balance!!.totalBalance, 1e-9)
        assertEquals(0.25, q.keyWindow!!.utilization, 1e-9)
    }

    // --- `deprecated key rate limit is ignored without degrading the section` (rate_limit objet ou chaîne)
    @Test
    fun `deprecated rate limit field is ignored`() = runTest {
        for (rateLimit in listOf("""{"requests":120,"interval":"10s"}""", """"unlimited"""", "null")) {
            val q = quota(
                """{"data":{"limit":50,"limit_remaining":38,"usage":12,"usage_daily":1.25,"usage_weekly":7.5,
                "usage_monthly":12,"limit_reset":"monthly","rate_limit":$rateLimit}}""",
                creditsStatus = 403
            )
            assertEquals(rateLimit, 0.24, q.keyWindow!!.utilization, 1e-9)
            assertEquals(rateLimit, 1.25, q.spending.first { it.period == SpendPeriod.TODAY }.amount, 1e-9)
        }
    }

    @Test
    fun `everything unavailable is an error`() = runTest {
        route(503 to """{"error":"unavailable"}""", 503 to """{"error":"unavailable"}""")
        val result = repository.fetchQuota()
        assertTrue(result is Result.Failure && result.error is AppError.ServiceUnavailable)
    }

    @Ignore("Divergence CodexBar : l'endpoint /activity (clé de management, dépense exacte sur 30 jours) n'est pas implémenté par Android.")
    @Test
    fun `activity rows aggregate into thirty day spend`() = runTest {
        route(200 to """{"data":{"limit":20,"usage":5}}""")
        repository.fetchQuota()
        assertTrue(requests.any { it.path!!.startsWith("/activity") })
    }
}
