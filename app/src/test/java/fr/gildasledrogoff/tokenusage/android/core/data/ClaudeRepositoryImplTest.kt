package fr.gildasledrogoff.tokenusage.core.data
import fr.gildasledrogoff.tokenusage.core.domain.model.toServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.ClaudeAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageSeverity
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeApiService
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeWebApiService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.security.InMemorySharedPreferences
import fr.gildasledrogoff.tokenusage.core.security.SecureStorageFactory
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import retrofit2.Retrofit
import java.time.Instant
class ClaudeRepositoryImplTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiService: ClaudeApiService
    private lateinit var webApiService: ClaudeWebApiService
    private lateinit var tokenRefreshService: ClaudeTokenRefreshService
    private lateinit var prefsManager: EncryptedPrefsManager
    private lateinit var repository: ClaudeRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val testCredential = Credential.ClaudeCredential(
        accessToken = "test-access-token",
        refreshToken = "test-refresh-token"
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
            .create(ClaudeApiService::class.java)
        tokenRefreshService = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(ClaudeTokenRefreshService::class.java)
        webApiService = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(ClaudeWebApiService::class.java)
        prefsManager = mock(EncryptedPrefsManager::class.java)
        givenAccount(testCredential)
        repository = ClaudeRepositoryImpl(apiService, webApiService, tokenRefreshService, prefsManager)
    }
    private fun givenAccount(credential: Credential) {
        `when`(prefsManager.getAccounts(AiService.CLAUDE)).thenReturn(listOf(ServiceAccount(ACCOUNT_ID, "", credential)))
    }
    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }
    @Test
    fun `fetchQuota returns success with all windows`() = runTest {
        val responseJson = """
        {
            "five_hour": { "utilization": 42, "resets_at": "2025-06-01T12:00:00Z" },
            "seven_day": { "utilization": 15, "resets_at": "2025-06-07T00:00:00Z" },
            "seven_day_oauth_apps": { "utilization": 10 },
            "seven_day_opus": { "utilization": 30 },
            "seven_day_sonnet": { "utilization": 20 },
            "iguana_necktie": { "utilization": 5 },
            "extra_usage": {
                "is_enabled": true,
                "monthly_limit": 50.0,
                "used_credits": 12.5,
                "utilization": 0.25,
                "currency": "USD"
            }
        }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))
        val result = repository.fetchQuota()
        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        assertEquals(AiService.CLAUDE, quotaInfo.service)
        assertEquals(6, quotaInfo.windows.size)
        assertEquals("5-Hour", quotaInfo.windows[0].label)
        assertEquals(0.42, quotaInfo.windows[0].utilization, 0.001)
        assertEquals("Extended", quotaInfo.windows[5].label)
        assertNotNull(quotaInfo.extraUsage)
        assertEquals(50.0, quotaInfo.extraUsage!!.monthlyLimit, 0.001)
        assertEquals(12.5, quotaInfo.extraUsage!!.usedCredits, 0.001)
    }
    @Test
    fun `fetchQuota handles response without iguana_necktie`() = runTest {
        val responseJson = """
        {
            "five_hour": { "utilization": 42 },
            "seven_day": { "utilization": 15 }
        }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))
        val result = repository.fetchQuota()
        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        assertEquals(2, quotaInfo.windows.size)
        assertTrue(quotaInfo.windows.none { it.label == "Extended" })
    }
    @Test
    fun `fetchQuota returns AuthError on 401`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        val result = repository.fetchQuota()
        assertTrue(result is Result.Failure)
        val error = (result as Result.Failure).error
        assertTrue(error is AppError.AuthError)
        assertTrue((error as AppError.AuthError).isTerminal)
    }
    @Test
    fun `fetchQuota returns TokenRevoked on OAuth 403`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error": {"type": "not_allowed_error", "message": "OAuth access has been revoked"}}""")
        )
        val result = repository.fetchQuota()
        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.TokenRevoked)
    }
    @Test
    fun `fetchQuota returns RateLimited on 429`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(429))
        val result = repository.fetchQuota()
        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.RateLimited)
    }
    @Test
    fun `fetchQuota returns CredentialNotFound when no credential saved`() = runTest {
        `when`(prefsManager.getAccounts(AiService.CLAUDE)).thenReturn(emptyList())
        val result = repository.fetchQuota()
        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.CredentialNotFound)
    }
    @Test
    fun `fetchQuota extracts tier from response headers`() = runTest {
        val responseJson = """
        {
            "five_hour": { "utilization": 42 }
        }
        """.trimIndent()
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(responseJson)
                .addHeader("anthropic-ratelimit-tier", "pro"))
        val result = repository.fetchQuota()
        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        assertEquals("Pro", quotaInfo.tier)
    }
    @Test
    fun `fetchQuota extracts tier from x-ratelimit-tier fallback header`() = runTest {
        val responseJson = """
        {
            "five_hour": { "utilization": 10 }
        }
        """.trimIndent()
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(responseJson)
                .addHeader("x-ratelimit-tier", "free"))
        val result = repository.fetchQuota()
        assertTrue(result is Result.Success)
        assertEquals("Free", (result as Result.Success).value.tier)
    }
    @Test
    fun `fetchQuota extracts tier from JWT access token`() = runTest {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"rate_limit_tier":"max","sub":"user"}""".toByteArray())
        val fakeJwt = "eyJhbGciOiJSUzI1NiJ9.$payload.fake-signature"
        val jwtCredential = Credential.ClaudeCredential(
            accessToken = fakeJwt,
            refreshToken = "test-refresh-token"
        )
        givenAccount(jwtCredential)
        val responseJson = """
        {
            "five_hour": { "utilization": 20 }
        }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))
        val result = repository.fetchQuota()
        assertTrue(result is Result.Success)
        assertEquals("Max", (result as Result.Success).value.tier)
    }
    @Test
    fun `fetchQuota extra_usage null when disabled`() = runTest {
        val responseJson = """
        {
            "five_hour": { "utilization": 42 },
            "extra_usage": { "is_enabled": false, "monthly_limit": 0, "used_credits": 0, "utilization": 0, "currency": "USD" }
        }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))
        val result = repository.fetchQuota()
        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        assertTrue(quotaInfo.extraUsage == null)
    }
    @Test
    fun `fetchQuota maps structured limits scope severity and weekly breakdown`() = runTest {
        val responseJson = """
        {
            "five_hour": { "utilization": 0, "resets_at": null, "locked_reason": null },
            "seven_day": { "utilization": 71, "resets_at": "2026-11-18T20:59:59Z" },
            "nimbus_quill": { "utilization": 33 },
            "limits": [
                { "kind": "session", "group": "session", "percent": 0, "severity": "normal", "is_active": false },
                { "kind": "weekly_all", "group": "weekly", "percent": 71, "severity": "normal", "is_active": false },
                { "kind": "weekly_scoped", "group": "weekly", "percent": 78, "severity": "warning",
                  "resets_at": "2026-11-18T20:59:59Z", "is_active": true,
                  "scope": { "model": { "id": null, "display_name": "Fable" } } }
            ],
            "seven_day_cowork": { "utilization": 12 },
            "seven_day_omelette": { "utilization": 5 },
            "seven_day_breakdown": {
                "as_of": "2026-11-15T16:08:47Z",
                "rows": [
                    { "key": "claude_code", "display_name": "Claude Code", "percent": 99 },
                    { "key": "chat", "display_name": "Chats", "percent": 0 },
                    { "key": "cowork", "display_name": "Cowork", "percent": 1 }
                ]
            }
        }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))
        val result = repository.fetchQuota()
        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        val labels = quotaInfo.windows.map { it.label }
        assertTrue(labels.containsAll(listOf("5-Hour", "7-Day", "Cowork", "Claude Design", "Fable")))
        assertEquals(1, labels.count { it == "5-Hour" })
        assertEquals(1, labels.count { it == "7-Day" })
        val fable = quotaInfo.windows.first { it.label == "Fable" }
        assertEquals(0.78, fable.utilization, 0.001)
        assertEquals(UsageSeverity.WARNING, fable.severity)
        assertEquals(Instant.parse("2026-11-18T20:59:59Z"), fable.resetsAt)
        assertEquals(0.12, quotaInfo.windows.first { it.label == "Cowork" }.utilization, 0.001)
        assertEquals(0.05, quotaInfo.windows.first { it.label == "Claude Design" }.utilization, 0.001)
        assertEquals(3, quotaInfo.weeklyBreakdown.size)
        assertEquals("Claude Code", quotaInfo.weeklyBreakdown[0].label)
        assertEquals(99.0, quotaInfo.weeklyBreakdown[0].percent, 0.001)
        assertTrue(quotaInfo.windows.none { it.label.contains("nimbus", ignoreCase = true) })
    }
    @Test
    fun `fetchQuota labels scoped limit without scope from kind`() = runTest {
        val responseJson = """
        {
            "five_hour": { "utilization": 10 },
            "limits": [
                { "kind": "weekly_scoped", "group": "weekly", "percent": 40, "severity": "normal", "is_active": false }
            ]
        }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))
        val result = repository.fetchQuota()
        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        val scoped = quotaInfo.windows.first { it.label == "Weekly scoped" }
        assertEquals(0.40, scoped.utilization, 0.001)
        assertEquals(UsageSeverity.NORMAL, scoped.severity)
    }
    private fun givenWebSessionCredential() {
        givenAccount(Credential.ClaudeWebCredential(accessToken = "sk-ant-sid01-test-session-key"))
    }
    @Test
    fun `web session discovers org and maps usage windows`() = runTest {
        givenWebSessionCredential()
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"uuid": "org-uuid-1", "name": "Test Org", "capabilities": ["chat"]}]"""
            )
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                    "five_hour": { "utilization": 55, "resets_at": "2025-06-01T12:00:00Z" },
                    "seven_day": { "utilization": 20 }
                }
                """.trimIndent()
            )
        )
        val result = repository.fetchQuota()
        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        assertEquals(AiService.CLAUDE, quotaInfo.service)
        assertEquals(2, quotaInfo.windows.size)
        assertEquals(0.55, quotaInfo.windows[0].utilization, 0.001)
        val orgRequest = mockWebServer.takeRequest()
        assertEquals("/api/organizations", orgRequest.path)
        assertTrue(orgRequest.getHeader("Cookie")!!.startsWith("sessionKey=sk-ant-sid01-"))
        val usageRequest = mockWebServer.takeRequest()
        assertEquals("/api/organizations/org-uuid-1/usage", usageRequest.path)
        verify(prefsManager).saveClaudeOrgId(ACCOUNT_ID, "org-uuid-1")
    }
    @Test
    fun `web session prefers first chat-capable org`() = runTest {
        givenWebSessionCredential()
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                [
                    {"uuid": "org-api-only", "capabilities": ["api"],
                     "name": "API Org"},
                    {"uuid": "org-chat", "capabilities": ["api", "chat"],
                     "name": "Chat Org"}
                ]
                """.trimIndent()
            )
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        repository.fetchQuota()
        mockWebServer.takeRequest()
        val usageRequest = mockWebServer.takeRequest()
        assertEquals("/api/organizations/org-chat/usage", usageRequest.path)
    }
    @Test
    fun `web session returns terminal AuthError on 401`() = runTest {
        givenWebSessionCredential()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        val result = repository.fetchQuota()
        assertTrue(result is Result.Failure)
        val error = (result as Result.Failure).error
        assertTrue(error is AppError.AuthError)
        assertTrue((error as AppError.AuthError).isTerminal)
    }
    @Test
    fun `web session returns TokenRevoked when org discovery answers 403`() = runTest {
        givenWebSessionCredential()
        mockWebServer.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error": {"type": "authentication_error", "message": "session revoked"}}""")
        )
        val result = repository.fetchQuota()
        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.TokenRevoked)
    }
    @Test
    fun `web session returns TokenRevoked when usage answers 403`() = runTest {
        givenWebSessionCredential()
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"uuid": "org-uuid-1", "name": "Test Org", "capabilities": ["chat"]}]"""
            )
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error": {"type": "not_allowed_error", "message": "revoked"}}""")
        )
        val result = repository.fetchQuota()
        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.TokenRevoked)
    }
    @Test
    fun `web session keeps network error on 403 HTML page instead of TokenRevoked`() = runTest {
        givenWebSessionCredential()
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"uuid": "org-uuid-1", "name": "Test Org", "capabilities": ["chat"]}]"""
            )
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("<html><body>Access denied (cloudflare challenge)</body></html>")
        )
        val result = repository.fetchQuota()
        assertTrue(result is Result.Failure)
        val error = (result as Result.Failure).error
        assertTrue(error is AppError.NetworkError)
        assertTrue(error !is AppError.TokenRevoked)
    }
    @Test
    fun `web session rejects malformed session key without network call`() = runTest {
        givenAccount(Credential.ClaudeWebCredential(accessToken = "not-a-session-key"))
        val result = repository.fetchQuota()
        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.AuthError)
        assertEquals(0, mockWebServer.requestCount)
    }
    @Test
    fun `web session accepts current sid02 key generation`() = runTest {
        givenAccount(Credential.ClaudeWebCredential(accessToken = "sk-ant-sid02-test-session-key"))
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"uuid": "org-uuid-1", "name": "Test Org", "capabilities": ["chat"]}]"""
            )
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                    "five_hour": { "utilization": 10 },
                    "seven_day": { "utilization": 5 }
                }
                """.trimIndent()
            )
        )
        val result = repository.fetchQuota()
        assertTrue(result is Result.Success)
        val orgRequest = mockWebServer.takeRequest()
        assertTrue(orgRequest.getHeader("Cookie")!!.startsWith("sessionKey=sk-ant-sid02-"))
    }
    @Test
    fun `web session retries once with fresh org when cached org is stale`() = runTest {
        givenWebSessionCredential()
        `when`(prefsManager.getClaudeOrgId(ACCOUNT_ID)).thenReturn("stale-org-id")
        mockWebServer.enqueue(MockResponse().setResponseCode(404))
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"uuid": "fresh-org", "capabilities": ["chat"]}]"""
            )
        )
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"five_hour": {"utilization": 10}}"""
            )
        )
        val result = repository.fetchQuota()
        assertTrue(result is Result.Success)
        assertEquals(1, (result as Result.Success).value.windows.size)
        verify(prefsManager).clearClaudeOrgId(ACCOUNT_ID)
        verify(prefsManager).saveClaudeOrgId(ACCOUNT_ID, "fresh-org")
        mockWebServer.takeRequest()
        mockWebServer.takeRequest()
        val retryUsage = mockWebServer.takeRequest()
        assertEquals("/api/organizations/fresh-org/usage", retryUsage.path)
    }
    @Test
    fun `fetchQuota authenticates with that account's own token`() = runTest {
        val work = ClaudeAccount("work", "Work", Credential.ClaudeCredential("work-token", "work-refresh"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"five_hour": {"utilization": 10}}"""))
        val result = repository.fetchQuota(work.toServiceAccount())
        assertTrue(result is Result.Success)
        assertEquals("Bearer work-token", mockWebServer.takeRequest().getHeader("Authorization"))
    }
    @Test
    fun `refreshed OAuth token is written back to its own account only`() = runTest {
        val store = InMemorySharedPreferences()
        val factory = mock(SecureStorageFactory::class.java)
        `when`(factory.credentials).thenReturn(store)
        val realPrefs = EncryptedPrefsManager(factory)
        val personal = ClaudeAccount("perso", "Perso", Credential.ClaudeWebCredential("sk-ant-sid02-perso"))
        val work = ClaudeAccount("work", "Work", Credential.ClaudeCredential("old-token", "old-refresh"))
        realPrefs.saveClaudeAccounts(listOf(personal, work))
        val repo = ClaudeRepositoryImpl(apiService, webApiService, tokenRefreshService, realPrefs)
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token": "new-token", "refresh_token": "new-refresh", "expires_in": 3600}""")
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"five_hour": {"utilization": 10}}"""))
        val result = repo.fetchQuota(work.toServiceAccount())
        assertTrue(result is Result.Success)
        val stored = realPrefs.getClaudeAccounts()
        assertEquals(listOf("perso", "work"), stored.map { it.id })
        assertEquals(personal, stored[0])
        val refreshed = stored[1].credential as Credential.ClaudeCredential
        assertEquals("Work", stored[1].label)
        assertEquals("new-token", refreshed.accessToken)
        assertEquals("new-refresh", refreshed.refreshToken)
        assertNotNull(refreshed.expiresAt)
    }
    @Test
    fun `validateAccount targets the requested account`() = runTest {
        val perso = ClaudeAccount("perso", "", Credential.ClaudeCredential("perso-token", null))
        val work = ClaudeAccount("work", "", Credential.ClaudeCredential("work-token", null))
        `when`(prefsManager.getAccounts(AiService.CLAUDE)).thenReturn(listOf(perso, work).map { it.toServiceAccount() })
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"five_hour": {"utilization": 10}}""")
        )
        val result = repository.validateAccount("work")
        assertTrue(result is Result.Success)
        assertEquals("Bearer work-token", mockWebServer.takeRequest().getHeader("Authorization"))
    }
    @Test
    fun `fetchQuota returns ParseError when 200 response has no recognized windows`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val result = repository.fetchQuota()
        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.ParseError)
    }
    @Test
    fun `golden sample usage claude json parses into windows`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(loadSample()))
        val result = repository.fetchQuota()
        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        val labels = quotaInfo.windows.map { it.label }
        assertTrue("windows: $labels", labels.containsAll(listOf("5-Hour", "7-Day")))
        val fiveHour = quotaInfo.windows.first { it.label == "5-Hour" }
        assertNotNull("resets_at with +00:00 offset failed to parse", fiveHour.resetsAt)
        assertTrue(fiveHour.utilization in 0.0..1.0)
        assertTrue("windows: $labels", labels.any { it == "Fable" || it == "Weekly scoped" })
        assertTrue(quotaInfo.weeklyBreakdown.isNotEmpty())
    }
    private fun loadSample(): String =
        checkNotNull(javaClass.classLoader?.getResource(SAMPLE_RESOURCE)) {
            "$SAMPLE_RESOURCE not found in test resources"
        }.readText()
    private companion object {
        const val ACCOUNT_ID = "claude-account-1"
        const val SAMPLE_RESOURCE = "usage.claude.json"
    }
}
