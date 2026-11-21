package fr.gildasledrogoff.tokenusage.core.parity

import fr.gildasledrogoff.tokenusage.core.data.ZaiRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.network.zai.ZaiApiService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant

class ZaiCodexBarParityTest {
    private lateinit var env: ParityServer
    private lateinit var repository: ZaiRepositoryImpl

    @Before
    fun setUp() {
        env = ParityServer()
        val prefs = mock(EncryptedPrefsManager::class.java)
        `when`(prefs.getAccounts(AiService.ZAI)).thenReturn(
            listOf(ServiceAccount("zai-1", "", Credential.ZaiCredential("fixture-key")))
        )
        repository = ZaiRepositoryImpl(env.service(ZaiApiService::class.java), prefs)
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

    private val quotaFixture = """
        {"code":200,"msg":"success","success":true,"data":{"planName":"Pro","limits":[
          {"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":25,"nextResetTime":1785816000000},
          {"type":"TOKENS_LIMIT","unit":6,"number":1,"percentage":9,"nextResetTime":1786291200000},
          {"type":"TIME_LIMIT","unit":5,"number":1,"usage":1000,"currentValue":224,"remaining":776,
           "percentage":22,"usageDetails":[{"modelCode":"search-prime","usage":210}]}
        ]}}
    """

    @Test
    fun `maps session weekly and mcp windows`() = runTest {
        val q = quota(quotaFixture)
        assertEquals(3, q.windows.size)
        assertEquals(0.25, q.windows[0].utilization, 1e-9)
        assertEquals(Instant.ofEpochMilli(1785816000000L), q.windows[0].resetsAt)
        assertEquals(0.09, q.windows[1].utilization, 1e-9)
        val request = env.server.takeRequest()
        assertEquals("/api/monitor/usage/quota/limit", request.path)
        assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
    }

    @Test
    fun `plan name is read from planName`() = runTest {
        assertEquals("Pro", quota(quotaFixture).tier)
    }

    @Test
    fun `percent is recomputed from usage and current value`() = runTest {
        val q = quota(quotaFixture)
        assertEquals(0.224, q.windows[2].utilization, 1e-9)
    }

    @Test
    fun `explicit zero usage remains a real window`() = runTest {
        for (type in listOf("TOKENS_LIMIT", "CREDIT_LIMIT", "TIME_LIMIT")) {
            val q = quota("""{"code":200,"success":true,"data":{"limits":[{"type":"$type","unit":3,"number":5,"percentage":0}]}}""")
            assertEquals(type, 1, q.windows.size)
            assertEquals(type, 0.0, q.windows[0].utilization, 0.0)
        }
    }

    @Test
    fun `rolling 30 day limits keep their percentage`() = runTest {
        for (type in listOf("TOKENS_LIMIT", "CREDIT_LIMIT")) {
            val q = quota(
                """{"code":200,"msg":"success","success":true,"data":{"limits":[
                  {"type":"$type","unit":1,"number":30,"percentage":50,"nextResetTime":1787112000000}]}}"""
            )
            assertEquals(0.5, q.windows.single().utilization, 1e-9)
            assertEquals(Instant.ofEpochMilli(1787112000000L), q.windows.single().resetsAt)
        }
    }

    @Test
    fun `points pool envelope without limits is not invented as usage`() = runTest {
        val result = fetch("""{"code":200,"success":true,"data":{"pointsPool":{"remaining":800}}}""")
        assertTrue(result is Result.Failure && result.error is AppError.ParseError)
    }

    @Test
    fun `unknown limit types never fabricate quota`() = runTest {
        for (limit in listOf(
            """{"type":"FUTURE_LIMIT","unit":3,"number":5,"percentage":40}""",
            """{"type":"FUTURE_POINTS_POOL","pointsRemaining":800}"""
        )) {
            val result = fetch("""{"code":200,"success":true,"data":{"planName":"Pro","limits":[$limit]}}""")
            val windows = (result as? Result.Success)?.value?.windows.orEmpty()
            assertTrue("Fenêtre fabriquée pour $limit : $windows", windows.isEmpty())
        }
    }

    @Test
    fun `unknown extra limits do not add windows next to recognized ones`() = runTest {
        val q = quota(
            """{"code":200,"success":true,"data":{"limits":[
              {"type":"TOKENS_LIMIT","unit":3,"number":5,"percentage":25},
              {"type":"FUTURE_POINTS_POOL","pointsRemaining":800}]}}"""
        )
        assertEquals(1, q.windows.size)
        assertEquals(0.25, q.windows[0].utilization, 1e-9)
    }

    @Test
    fun `limits without a valid type are rejected`() = runTest {
        for (limit in listOf(
            """{"unit":3,"number":5,"percentage":25}""",
            """{"type":null,"unit":3,"number":5,"percentage":25}""",
            """{"type":42,"unit":3,"number":5,"percentage":25}"""
        )) {
            val result = fetch("""{"code":200,"success":true,"data":{"limits":[$limit]}}""")
            assertTrue("Réponse acceptée à tort : $limit", result is Result.Failure)
        }
    }

    @Test
    fun `unsuccessful envelope is an error`() = runTest {
        val result = fetch("""{"code":500,"msg":"boom","success":false}""")
        assertTrue(result is Result.Failure && (result.error as AppError.ParseError).message == "boom")
    }
}
