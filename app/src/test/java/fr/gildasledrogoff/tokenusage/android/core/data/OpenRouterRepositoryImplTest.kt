package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import fr.gildasledrogoff.tokenusage.core.network.openrouter.OpenRouterApiService
import fr.gildasledrogoff.tokenusage.core.network.openrouter.OpenRouterDto
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import retrofit2.Retrofit
import java.time.Instant

class OpenRouterRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var prefsManager: EncryptedPrefsManager
    private lateinit var repository: OpenRouterRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenRouterApiService::class.java)
        prefsManager = mock(EncryptedPrefsManager::class.java)
        `when`(prefsManager.getAccounts(AiService.OPENROUTER)).thenReturn(listOf(ServiceAccount("id-1", "", Credential.ApiKeyCredential("sk-or-v1-x"))))
        repository = OpenRouterRepositoryImpl(api, prefsManager)
    }

    @After
    fun tearDown() = server.shutdown()

    private val cappedKey = """
        {"data":{"label":"sk-or-v1-x…","limit":30.0,"limit_reset":"monthly","limit_remaining":18.0,
                 "usage":40.0,"usage_daily":0.5,"usage_weekly":3.0,"usage_monthly":12.0,"is_free_tier":false}}
    """.trimIndent()

    @Test
    fun `capped key gives a gauge, spend rows and the credit balance`() = runTest {
        server.enqueue(MockResponse().setBody(cappedKey))
        server.enqueue(MockResponse().setBody("""{"data":{"total_credits":5.0,"total_usage":3.1}}"""))

        val quota = (repository.fetchQuota() as Result.Success).value

        assertEquals(1, quota.windows.size)
        assertEquals("API key limit", quota.windows[0].label)
        assertEquals(0.4, quota.windows[0].utilization, 1e-9)
        assertEquals(
            listOf(SpendPeriod.TODAY, SpendPeriod.THIS_WEEK, SpendPeriod.THIS_MONTH),
            quota.spending.map { it.period }
        )
        assertEquals(1.9, quota.accountBalances.single().balance!!.totalBalance, 1e-9)

        val keyRequest = server.takeRequest()
        assertEquals("/key", keyRequest.path)
        assertEquals("Bearer sk-or-v1-x", keyRequest.getHeader("Authorization"))
        assertEquals("/credits", server.takeRequest().path)
    }

    @Test
    fun `refused credits keep the key data`() = runTest {
        server.enqueue(MockResponse().setBody(cappedKey))
        server.enqueue(MockResponse().setResponseCode(403))

        val quota = (repository.fetchQuota() as Result.Success).value

        assertEquals(1, quota.windows.size)
        assertTrue(quota.accountBalances.isEmpty())
    }

    @Test
    fun `credits payload without amounts is ignored instead of showing a zero balance`() = runTest {
        server.enqueue(MockResponse().setBody(cappedKey))
        server.enqueue(MockResponse().setBody("""{"data":{}}"""))

        val quota = (repository.fetchQuota() as Result.Success).value

        assertTrue(quota.accountBalances.isEmpty())
    }

    @Test
    fun `uncapped key has no gauge`() {
        val quota = OpenRouterRepositoryImpl.toQuotaInfo(
            OpenRouterDto.KeyData(limit = null, usageMonthly = 2.0),
            credits = null
        )
        assertTrue(quota.windows.isEmpty())
        assertEquals(2.0, quota.spending.single().amount, 1e-9)
    }

    @Test
    fun `without remaining amount the reset window spend is used`() {
        val key = OpenRouterDto.KeyData(limit = 10.0, limitReset = "daily", usageDaily = 2.5, usage = 9.0)
        assertEquals(0.25, OpenRouterRepositoryImpl.keyLimitUtilization(key)!!, 1e-9)
    }

    @Test
    fun `next reset follows the declared window at midnight UTC`() {
        val now = Instant.parse("2026-11-15T20:00:00Z")
        assertEquals(Instant.parse("2026-11-16T00:00:00Z"), OpenRouterRepositoryImpl.nextReset("daily", now))
        assertEquals(Instant.parse("2026-11-16T00:00:00Z"), OpenRouterRepositoryImpl.nextReset("weekly", now))
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), OpenRouterRepositoryImpl.nextReset("monthly", now))
        assertNull(OpenRouterRepositoryImpl.nextReset(null, now))
    }

    @Test
    fun `failing key endpoint still shows the credit balance`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("""{"data":{"total_credits":5.0,"total_usage":3.1}}"""))

        val quota = (repository.fetchQuota() as Result.Success).value

        assertTrue(quota.windows.isEmpty())
        assertTrue(quota.spending.isEmpty())
        assertEquals(1.9, quota.accountBalances.single().balance!!.totalBalance, 1e-9)
    }

    @Test
    fun `key error is reported when credits are unusable too`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody("""{"data":{}}"""))

        assertEquals(AppError.RateLimited, (repository.fetchQuota() as Result.Failure).error)
    }

    @Test
    fun `401 is a terminal auth error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        val error = (repository.fetchQuota() as Result.Failure).error

        assertTrue(error is AppError.AuthError && error.isTerminal)
    }
}
