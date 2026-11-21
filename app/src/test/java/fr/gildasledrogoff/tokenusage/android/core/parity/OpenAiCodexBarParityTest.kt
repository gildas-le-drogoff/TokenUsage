package fr.gildasledrogoff.tokenusage.core.parity

import fr.gildasledrogoff.tokenusage.core.data.OpenAiRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import fr.gildasledrogoff.tokenusage.core.network.openai.OpenAiApiService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class OpenAiCodexBarParityTest {
    private lateinit var env: ParityServer
    private lateinit var repository: OpenAiRepositoryImpl

    private val now = Instant.ofEpochSecond(1_700_179_200)
    private val emptyPage = """{"object":"page","data":[],"has_more":false,"next_page":null}"""

    @Before
    fun setUp() {
        env = ParityServer()
        val prefs = mock(EncryptedPrefsManager::class.java)
        `when`(prefs.getAccounts(AiService.OPENAI)).thenReturn(
            listOf(ServiceAccount("oa-1", "", Credential.ApiKeyCredential("sk-test")))
        )
        repository = OpenAiRepositoryImpl(
            env.service(OpenAiApiService::class.java), prefs, Clock.fixed(now, ZoneOffset.UTC)
        )
    }

    @After
    fun tearDown() = env.close()

    private suspend fun fetch(vararg pages: String): Result<QuotaInfo, AppError> {
        pages.forEach { env.enqueue(200, it) }
        return repository.fetchQuota()
    }

    private fun QuotaInfo.spent(period: SpendPeriod) = spending.first { it.period == period }.amount

    @Test
    fun `daily costs are summed including string amounts`() = runTest {
        val costs = """
        {
          "object": "page",
          "data": [
            {
              "object": "bucket", "start_time": 1700000000, "end_time": 1700086400,
              "results": [
                { "object": "organization.costs.result", "amount": { "value": 12.50, "currency": "usd" }, "line_item": "Text tokens" },
                { "object": "organization.costs.result", "amount": { "value": "2.25", "currency": "usd" }, "line_item": "Web search tool calls" }
              ]
            },
            {
              "object": "bucket", "start_time": 1700086400, "end_time": 1700172800,
              "results": [
                { "object": "organization.costs.result", "amount": { "value": 4.00, "currency": "usd" }, "line_item": "Text tokens" }
              ]
            }
          ],
          "has_more": false, "next_page": null
        }
        """
        val q = (fetch(costs) as Result.Success).value
        assertEquals(18.75, q.spent(SpendPeriod.LAST_30_DAYS), 1e-9)
        assertEquals(18.75, q.spent(SpendPeriod.LAST_7_DAYS), 1e-9)
        assertEquals("USD", q.spending.first().currency)
    }

    @Test
    fun `nonfinite cost strings are rejected`() = runTest {
        for (value in listOf("NaN", "Infinity", "-Infinity", "1e309", "-1e309")) {
            env.enqueue(
                200,
                """{"data":[{"start_time":1700000000,"end_time":1700086400,"results":[{"amount":{"value":"$value","currency":"usd"}}]}],"has_more":false,"next_page":null}"""
            )
            val result = repository.fetchQuota()
            assertTrue(value, result is Result.Failure)
        }
    }

    @Test
    fun `follows pagination cursor`() = runTest {
        val page1 = """{"data":[{"start_time":1700000000,"end_time":1700086400,"results":[{"amount":{"value":1.0,"currency":"usd"}}]}],"has_more":true,"next_page":"costs_page_2"}"""
        val page2 = """{"data":[{"start_time":1700086400,"end_time":1700172800,"results":[{"amount":{"value":3.0,"currency":"usd"}}]}],"has_more":false,"next_page":null}"""
        val q = (fetch(page1, page2) as Result.Success).value
        assertEquals(4.0, q.spent(SpendPeriod.LAST_30_DAYS), 1e-9)
        assertEquals(null, env.server.takeRequest().requestUrl!!.queryParameter("page"))
        assertEquals("costs_page_2", env.server.takeRequest().requestUrl!!.queryParameter("page"))
    }

    @Test
    fun `repeated cursor is rejected`() = runTest {
        val page = """{"data":[],"has_more":true,"next_page":"same"}"""
        val result = fetch(page, page)
        assertTrue(result is Result.Failure && result.error is AppError.ParseError)
    }

    @Test
    fun `missing cursor is rejected`() = runTest {
        val result = fetch("""{"data":[],"has_more":true,"next_page":null}""")
        assertTrue(result is Result.Failure && result.error is AppError.ParseError)
    }

    @Test
    fun `page without data is rejected`() = runTest {
        val result = fetch("""{"object":"page","has_more":false,"next_page":null}""")
        assertTrue(result is Result.Failure)
    }

    @Test
    fun `page without has_more is rejected`() = runTest {
        val result = fetch("""{"object":"page","data":[],"next_page":null}""")
        assertTrue(result is Result.Failure)
    }

    @Ignore("Divergence CodexBar : Android n'interroge ni `usage/completions` (tokens, requêtes, modèles) ni le filtre `project_ids`.")
    @Test
    fun `completions usage endpoint is queried`() = runTest {
        fetch(emptyPage)
        assertEquals(2, env.server.requestCount)
    }

    @Test
    fun `401 and 403 require an admin key`() = runTest {
        for (code in listOf(401, 403)) {
            env.enqueue(code, "")
            val result = repository.fetchQuota()
            assertTrue("$code", result is Result.Failure && (result.error as? AppError.AuthError)?.message == "Admin API key required")
        }
    }
}
