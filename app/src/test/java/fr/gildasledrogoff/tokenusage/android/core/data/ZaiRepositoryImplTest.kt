package fr.gildasledrogoff.tokenusage.core.data
import fr.gildasledrogoff.tokenusage.core.domain.model.toServiceAccount
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ZaiAccount
import fr.gildasledrogoff.tokenusage.core.network.zai.ZaiApiService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import retrofit2.Retrofit
import java.time.Instant
class ZaiRepositoryImplTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiService: ZaiApiService
    private lateinit var prefsManager: EncryptedPrefsManager
    private lateinit var repository: ZaiRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val account = ZaiAccount(id = "id-1", label = "Personal", apiKey = "zai-key-1")
    private fun quotaBody(
        level: String = "pro",
        percentage: Int = 42,
        code: Int = 200,
        success: Boolean = true
    ): String {
        return """
        {
            "code": $code,
            "msg": "ok",
            "success": $success,
            "data": {
                "level": "$level",
                "limits": [
                    {
                        "type": "TOKENS_LIMIT",
                        "unit": 3,
                        "number": 5,
                        "percentage": $percentage,
                        "nextResetTime": 1773600000000,
                        "usage": 42,
                        "remaining": 58
                    },
                    {
                        "type": "TIME_LIMIT",
                        "unit": 5,
                        "number": 1,
                        "percentage": 10,
                        "nextResetTime": 1775078400000
                    },
                    {
                        "type": "TOKENS_LIMIT",
                        "unit": 5,
                        "number": 1,
                        "percentage": 7,
                        "nextResetTime": 1775078400000
                    }
                ]
            }
        }
        """.trimIndent()
    }
    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        val client = OkHttpClient.Builder().build()
        val contentType = "application/json".toMediaType()
        apiService = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(ZaiApiService::class.java)
        prefsManager = mock(EncryptedPrefsManager::class.java)
        `when`(prefsManager.getAccounts(AiService.ZAI)).thenReturn(listOf(account.toServiceAccount()))
        repository = ZaiRepositoryImpl(apiService, prefsManager)
    }
    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }
    @Test
    fun `fetchQuotaForAccount parses session tools and quota windows`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(quotaBody()))
        val result = repository.fetchQuota(account.toServiceAccount())
        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        assertEquals(AiService.ZAI, quotaInfo.service)
        assertEquals("Pro", quotaInfo.tier)
        assertNull(quotaInfo.extraUsage)
        val windows = quotaInfo.windows
        assertEquals(3, windows.size)
        assertEquals("Session (5h)", windows[0].label)
        assertEquals(0.42, windows[0].utilization, 0.0001)
        assertEquals(Instant.ofEpochMilli(1773600000000L), windows[0].resetsAt)
        assertEquals("Monthly Tools", windows[1].label)
        assertEquals(0.10, windows[1].utilization, 0.0001)
        assertEquals("Monthly Quota", windows[2].label)
        assertEquals(0.07, windows[2].utilization, 0.0001)
    }
    @Test
    fun `fetchQuotaForAccount drops Monthly Tools when hidden in settings`() = runTest {
        `when`(prefsManager.isZaiMonthlyToolsHidden()).thenReturn(true)
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(quotaBody()))
        val result = repository.fetchQuota(account.toServiceAccount())
        assertTrue(result is Result.Success)
        val labels = (result as Result.Success).value.windows.map { it.label }
        assertEquals(listOf("Session (5h)", "Monthly Quota"), labels)
    }
    @Test
    fun `fetchQuotaForAccount sends bearer token and accept language header`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(quotaBody()))
        repository.fetchQuota(account.toServiceAccount())
        val request = mockWebServer.takeRequest()
        assertEquals("/api/monitor/usage/quota/limit", request.path)
        assertEquals("Bearer zai-key-1", request.getHeader("Authorization"))
        assertEquals("en-US,en", request.getHeader("Accept-Language"))
    }
    @Test
    fun `fetchQuotaForAccount maps utilization above 100 percent to full`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(quotaBody(percentage = 120)))
        val result = repository.fetchQuota(account.toServiceAccount())
        assertTrue(result is Result.Success)
        val window = (result as Result.Success).value.windows.first()
        assertEquals(1.0, window.utilization, 0.0001)
    }
    @Test
    fun `fetchQuotaForAccount returns terminal AuthError on code 1001`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(quotaBody(code = 1001, success = false))
        )
        val result = repository.fetchQuota(account.toServiceAccount())
        assertTrue(result is Result.Failure)
        val error = (result as Result.Failure).error
        assertTrue(error is AppError.AuthError)
        assertTrue((error as AppError.AuthError).isTerminal)
    }
    @Test
    fun `fetchQuotaForAccount returns terminal AuthError on http 401`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        val result = repository.fetchQuota(account.toServiceAccount())
        assertTrue(result is Result.Failure)
        val error = (result as Result.Failure).error
        assertTrue(error is AppError.AuthError)
        assertTrue((error as AppError.AuthError).isTerminal)
    }
    @Test
    fun `fetchQuotaForAccount returns RateLimited on 429`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(429))
        val result = repository.fetchQuota(account.toServiceAccount())
        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.RateLimited)
    }
    @Test
    fun `fetchQuotaForAccount returns ParseError when success flag is false`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(quotaBody(success = false))
        )
        val result = repository.fetchQuota(account.toServiceAccount())
        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.ParseError)
    }
    @Test
    fun `fetchQuotaForAccount returns CredentialNotFound when api key is blank`() = runTest {
        val result = repository.fetchQuota(account.copy(apiKey = " ").toServiceAccount())
        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.CredentialNotFound)
    }
    @Test
    fun `fetchQuota uses the first configured account`() = runTest {
        val second = ZaiAccount(id = "id-2", label = "Work", apiKey = "zai-key-2")
        `when`(prefsManager.getAccounts(AiService.ZAI)).thenReturn(listOf(account, second).map { it.toServiceAccount() })
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(quotaBody()))
        val result = repository.fetchQuota()
        assertTrue(result is Result.Success)
        val request = mockWebServer.takeRequest()
        assertEquals("Bearer zai-key-1", request.getHeader("Authorization"))
    }
    @Test
    fun `fetchQuota returns CredentialNotFound when no account configured`() = runTest {
        `when`(prefsManager.getAccounts(AiService.ZAI)).thenReturn(emptyList())
        val result = repository.fetchQuota()
        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.CredentialNotFound)
    }
    @Test
    fun `validateAccount returns Success on valid key`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(quotaBody()))
        val result = repository.validateAccount("id-1")
        assertTrue(result is Result.Success)
    }
    @Test
    fun `validateAccount returns AuthError on invalid key`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        val result = repository.validateAccount("id-1")
        assertTrue(result is Result.Failure)
        val error = (result as Result.Failure).error
        assertTrue(error is AppError.AuthError)
        assertTrue((error as AppError.AuthError).isTerminal)
    }
    @Test
    fun `validateAccount returns CredentialNotFound for unknown account id`() = runTest {
        val result = repository.validateAccount("unknown-id")
        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.CredentialNotFound)
    }
}
