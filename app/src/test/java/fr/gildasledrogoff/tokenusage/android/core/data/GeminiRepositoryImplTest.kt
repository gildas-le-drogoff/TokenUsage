package fr.gildasledrogoff.tokenusage.core.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiApiService
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiDto
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiProjectsService
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.test.runTest
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import retrofit2.Retrofit

class GeminiRepositoryImplTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiService: GeminiApiService
    private lateinit var tokenRefreshService: GeminiTokenRefreshService
    private lateinit var prefsManager: EncryptedPrefsManager
    private lateinit var repository: GeminiRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    private val testCredential = Credential.GeminiCredential(
        accessToken = "test-access-token",
        refreshToken = "test-refresh-token",
        expiresAtMs = System.currentTimeMillis() + 3600_000,
        oauthClientId = "test-client-id",
        oauthClientSecret = "test-client-secret"
    )

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
            .create(GeminiApiService::class.java)

        tokenRefreshService = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(GeminiTokenRefreshService::class.java)

        val projectsService = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(GeminiProjectsService::class.java)

        prefsManager = mock(EncryptedPrefsManager::class.java)
        `when`(prefsManager.loadCredential(AiService.GEMINI)).thenReturn(testCredential)

        repository = GeminiRepositoryImpl(apiService, projectsService, tokenRefreshService, prefsManager)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `fetchQuota with string projectId`() = runTest {
        val loadResponse = """
        {
            "cloudaicompanionProject": "my-project-123",
            "currentTier": { "id": "free-tier" }
        }
        """.trimIndent()

        val quotaResponse = """
        {
            "buckets": [
                { "modelId": "gemini-2.0-flash", "remainingFraction": 0.73, "resetTime": "2025-06-01T12:00:00Z" },
                { "modelId": "gemini-2.0-pro", "remainingFraction": 0.50, "resetTime": "2025-06-01T12:00:00Z" }
            ]
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(loadResponse))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(quotaResponse))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        assertEquals(AiService.GEMINI, quotaInfo.service)
        assertEquals("Free", quotaInfo.tier)
        assertEquals(2, quotaInfo.windows.size)
        assertEquals("Pro", quotaInfo.windows[0].label)
        assertEquals(0.50, quotaInfo.windows[0].utilization, 0.01)
        assertEquals("Flash", quotaInfo.windows[1].label)
        assertEquals(0.27, quotaInfo.windows[1].utilization, 0.01)
    }

    @Test
    fun `fetchQuota with object projectId`() = runTest {
        val loadResponse = """
        {
            "cloudaicompanionProject": { "id": "my-project-456" },
            "currentTier": { "id": "standard-tier" }
        }
        """.trimIndent()

        val quotaResponse = """
        {
            "buckets": [
                { "modelId": "gemini-2.0-flash", "remainingFraction": 0.5 }
            ]
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(loadResponse))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(quotaResponse))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        assertEquals("Paid", quotaInfo.tier)
    }

    @Test
    fun `fetchQuota deduplicates buckets by modelId`() = runTest {
        val loadResponse = """
        {
            "cloudaicompanionProject": "proj",
            "currentTier": { "id": "free-tier" }
        }
        """.trimIndent()

        val quotaResponse = """
        {
            "buckets": [
                { "modelId": "gemini-2.0-flash", "remainingFraction": 0.80 },
                { "modelId": "gemini-2.0-flash", "remainingFraction": 0.30 },
                { "modelId": "gemini-2.0-pro", "remainingFraction": 0.60 }
            ]
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(loadResponse))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(quotaResponse))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        assertEquals(2, quotaInfo.windows.size)
        val flashWindow = quotaInfo.windows.first { it.label == "Flash" }
        assertEquals(0.70, flashWindow.utilization, 0.01)
        val proWindow = quotaInfo.windows.first { it.label == "Pro" }
        assertEquals(0.40, proWindow.utilization, 0.01)
    }

    @Test
    fun `fetchQuota returns AuthError on 401`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.AuthError)
        mockWebServer.takeRequest()
        assertEquals("/token", mockWebServer.takeRequest().path)
    }

    @Test
    fun `expired token is refreshed with a form-urlencoded request`() = runTest {
        val expired = testCredential.copy(expiresAtMs = System.currentTimeMillis() - 60_000)
        `when`(prefsManager.loadCredential(AiService.GEMINI)).thenReturn(expired)
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"access_token":"new-access","expires_in":3600}""")
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                    "cloudaicompanionProject": "proj",
                    "currentTier": { "id": "free-tier" }
                }
                """.trimIndent()
            )
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"buckets": [{"modelId": "gemini-2.0-flash", "remainingFraction": 0.5}]}"""
            )
        )

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val refreshRequest = mockWebServer.takeRequest()
        assertEquals("/token", refreshRequest.path)
        assertEquals("application/x-www-form-urlencoded", refreshRequest.getHeader("Content-Type"))
        val form = refreshRequest.body.readUtf8()
        assertTrue(form.contains("grant_type=refresh_token"))
        assertTrue(form.contains("refresh_token=test-refresh-token"))
        assertTrue(form.contains("client_id=test-client-id"))
        assertTrue(form.contains("client_secret=test-client-secret"))
    }

    @Test
    fun `flash lite is a separate family from flash`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                    "cloudaicompanionProject": "proj",
                    "currentTier": { "id": "free-tier" }
                }
                """.trimIndent()
            )
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                    "buckets": [
                        { "modelId": "gemini-2.5-pro", "remainingFraction": 0.90 },
                        { "modelId": "gemini-2.5-flash", "remainingFraction": 0.50 },
                        { "modelId": "gemini-2.5-flash-lite", "remainingFraction": 0.10 },
                        { "modelId": "gemini-unknown-model", "remainingFraction": 0.01 }
                    ]
                }
                """.trimIndent()
            )
        )

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val windows = (result as Result.Success).value.windows
        assertEquals(listOf("Pro", "Flash", "Flash Lite"), windows.map { it.label })
        assertEquals(0.10, windows[0].utilization, 1e-9)
        assertEquals(0.50, windows[1].utilization, 1e-9)
        assertEquals(0.90, windows[2].utilization, 1e-9)
    }

    @Test
    fun `paidTier name is the most specific plan signal`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                    "cloudaicompanionProject": "proj",
                    "currentTier": { "id": "free-tier" },
                    "paidTier": { "name": "Google One AI Pro" }
                }
                """.trimIndent()
            )
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"buckets": [{"modelId": "gemini-2.5-pro", "remainingFraction": 0.5}]}"""
            )
        )

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        assertEquals("Google One AI Pro", (result as Result.Success).value.tier)
    }

    private fun enqueue(body: String, code: Int = 200) =
        mockWebServer.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private fun idToken(claims: String): String {
        val encode = { text: String ->
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        }
        return "${encode("""{"alg":"RS256"}""")}.${encode(claims)}.signature"
    }

    private val shutdownLoadResponse = """
        {"cloudaicompanionProject":"proj","ineligibleTiers":[{"reasonCode":"UNSUPPORTED_CLIENT","reasonMessage":"Not supported","tierId":"free-tier"}]}
    """.trimIndent()
    private val oneBucket = """{"buckets": [{"modelId": "gemini-2.5-pro", "remainingFraction": 0.5}]}"""

    @Test
    fun `requests carry the Gemini CLI metadata and the project`() = runTest {
        enqueue("""{"cloudaicompanionProject": {"projectId": " proj-1 "}, "currentTier": {"id": "free-tier"}}""")
        enqueue(oneBucket)

        assertTrue(repository.fetchQuota() is Result.Success)

        val load = mockWebServer.takeRequest()
        assertEquals("/v1internal:loadCodeAssist", load.path)
        assertEquals("""{"metadata":{"ideType":"GEMINI_CLI","pluginType":"GEMINI"}}""", load.body.readUtf8())
        val quota = mockWebServer.takeRequest()
        assertEquals("/v1internal:retrieveUserQuota", quota.path)
        assertEquals("""{"project":"proj-1"}""", quota.body.readUtf8())
    }

    @Test
    fun `failed loadCodeAssist still fetches the quota with a discovered project`() = runTest {
        enqueue("boom", code = 500)
        enqueue(
            """{"projects":[{"projectId":"other"},{"projectId":"labelled","labels":{"generative-language":"1"}},
                {"projectId":"gen-lang-client-42"}]}"""
        )
        enqueue(oneBucket)

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        assertNull((result as Result.Success).value.tier)
        mockWebServer.takeRequest()
        assertEquals("/v1/projects", mockWebServer.takeRequest().path)
        assertEquals("""{"project":"labelled"}""", mockWebServer.takeRequest().body.readUtf8())
    }

    @Test
    fun `no project anywhere sends an empty quota request`() = runTest {
        enqueue("""{"currentTier": {"id": "free-tier"}}""")
        enqueue("{}", code = 403)
        enqueue(oneBucket)

        assertTrue(repository.fetchQuota() is Result.Success)

        mockWebServer.takeRequest()
        mockWebServer.takeRequest()
        assertEquals("{}", mockWebServer.takeRequest().body.readUtf8())
    }

    @Test
    fun `consumer shutdown answer is reported as unsupported client`() = runTest {
        `when`(prefsManager.loadCredential(AiService.GEMINI)).thenReturn(testCredential.copy(hostedDomain = ""))
        enqueue(shutdownLoadResponse)

        val result = repository.fetchQuota()

        assertEquals(AppError.ClientUnsupported(AiService.GEMINI), (result as Result.Failure).error)
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `shutdown wording in an error body is reported as unsupported client`() = runTest {
        `when`(prefsManager.loadCredential(AiService.GEMINI)).thenReturn(testCredential.copy(hostedDomain = ""))
        enqueue("""{"currentTier": {"id": "free-tier"}, "cloudaicompanionProject": "p"}""")
        enqueue("""{"error":{"message":"IneligibleTierError: please migrate"}}""", code = 403)

        val result = repository.fetchQuota()

        assertEquals(AppError.ClientUnsupported(AiService.GEMINI), (result as Result.Failure).error)
    }

    @Test
    fun `plain quota 403 stays a generic error`() = runTest {
        enqueue("""{"currentTier": {"id": "standard-tier"}, "cloudaicompanionProject": "p"}""")
        enqueue("""{"error":{"status":"PERMISSION_DENIED"}}""", code = 403)

        val result = repository.fetchQuota()

        assertTrue((result as Result.Failure).error is AppError.NetworkError)
    }

    @Test
    fun `shutdown verdict is rechecked once the Workspace domain is known`() = runTest {
        enqueue(shutdownLoadResponse)
        enqueue("""{"access_token":"new-access","expires_in":3600,"id_token":"${idToken("""{"hd":"example.com"}""")}"}""")
        enqueue(shutdownLoadResponse)
        enqueue(oneBucket)

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        mockWebServer.takeRequest()
        assertEquals("/token", mockWebServer.takeRequest().path)
    }

    @Test
    fun `free tier on a Workspace domain is the Workspace plan`() = runTest {
        `when`(prefsManager.loadCredential(AiService.GEMINI))
            .thenReturn(testCredential.copy(hostedDomain = "example.com"))
        enqueue("""{"cloudaicompanionProject": "proj", "currentTier": {"id": "free-tier"}}""")
        enqueue(oneBucket)

        val result = repository.fetchQuota()

        assertEquals("Workspace", (result as Result.Success).value.tier)
    }

    @Test
    fun `hosted domain is read from the id token`() {
        assertEquals("example.com", GeminiDto.hostedDomainOf(idToken("""{"email":"a@example.com","hd":"example.com"}""")))
        assertNull(GeminiDto.hostedDomainOf(idToken("""{"email":"a@gmail.com"}""")))
        assertNull(GeminiDto.hostedDomainOf("not-a-jwt"))
        assertNull(GeminiDto.hostedDomainOf(null))
        assertEquals("example.com", GeminiDto.refreshedHostedDomain(null, "example.com"))
        assertEquals("", GeminiDto.refreshedHostedDomain(idToken("""{"email":"a@gmail.com"}"""), null))
    }

    @Test
    fun `response without buckets is a parse error`() = runTest {
        enqueue("""{"cloudaicompanionProject": "proj", "currentTier": {"id": "free-tier"}}""")
        enqueue("""{"buckets": []}""")

        val result = repository.fetchQuota()

        assertTrue((result as Result.Failure).error is AppError.ParseError)
    }

    @Test
    fun `bucket without remaining fraction is skipped`() = runTest {
        enqueue("""{"cloudaicompanionProject": "proj", "currentTier": {"id": "free-tier"}}""")
        enqueue(
            """{"buckets": [{"modelId": "gemini-2.5-pro"},
                {"modelId": "gemini-2.5-flash", "remainingFraction": 0.25}]}"""
        )

        val windows = (repository.fetchQuota() as Result.Success).value.windows

        assertEquals(listOf("Flash"), windows.map { it.label })
        assertEquals(0.75, windows[0].utilization, 1e-9)
    }

    @Test
    fun `fetchQuota returns RateLimited on 429`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(429))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.RateLimited)
    }

    @Test
    fun `fetchQuota returns CredentialNotFound when no credential`() = runTest {
        `when`(prefsManager.loadCredential(AiService.GEMINI)).thenReturn(null)

        val result = repository.fetchQuota()

        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.CredentialNotFound)
    }
}
