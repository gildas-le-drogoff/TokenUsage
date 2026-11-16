package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.network.copilot.CopilotApiService
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

class CopilotRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var prefsManager: EncryptedPrefsManager
    private lateinit var repository: CopilotRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CopilotApiService::class.java)
        prefsManager = mock(EncryptedPrefsManager::class.java)
        `when`(prefsManager.loadCredential(AiService.COPILOT)).thenReturn(Credential.ApiKeyCredential("gho_token"))
        repository = CopilotRepositoryImpl(api, prefsManager)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun enqueue(body: String, code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test
    fun `premium and chat snapshots become monthly windows`() = runTest {
        enqueue(
            """
            {"login":"octocat","copilot_plan":"individual","quota_reset_date":"2026-12-01",
             "quota_snapshots":{
               "premium_interactions":{"entitlement":300,"remaining":90,"percent_remaining":30.0,"unlimited":false,"quota_id":"premium_interactions"},
               "chat":{"entitlement":0,"remaining":0,"percent_remaining":100.0,"unlimited":true,"quota_id":"chat"},
               "completions":{"entitlement":500,"remaining":450,"unlimited":false,"quota_id":"completions"}
             }}
            """.trimIndent()
        )

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val quota = (result as Result.Success).value
        assertEquals("Individual", quota.tier)
        assertEquals(listOf("Premium requests", "Completions"), quota.windows.map { it.label })
        assertEquals(0.70, quota.windows[0].utilization, 1e-9)
        assertEquals(0.10, quota.windows[1].utilization, 1e-9)
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), quota.windows[0].resetsAt)
    }

    @Test
    fun `over quota usage is reported above 100 percent`() = runTest {
        enqueue(
            """{"copilot_plan":"individual","quota_snapshots":{
                "premium_interactions":{"entitlement":300,"remaining":-90,"percent_remaining":-30.0,"unlimited":false}}}"""
        )

        val quota = (repository.fetchQuota() as Result.Success).value

        assertEquals(1.30, quota.windows.single().utilization, 1e-9)
    }

    @Test
    fun `monthly and limited counters are used when snapshots are missing`() = runTest {
        enqueue(
            """{"copilot_plan":"free","monthly_quotas":{"chat":50,"completions":"2000"},
                "limited_user_quotas":{"chat":10,"completions":500}}"""
        )

        val quota = (repository.fetchQuota() as Result.Success).value

        assertEquals(listOf("Chat", "Completions"), quota.windows.map { it.label })
        assertEquals(0.80, quota.windows[0].utilization, 1e-9)
        assertEquals(0.75, quota.windows[1].utilization, 1e-9)
    }

    @Test
    fun `monthly counters without the remaining amount give no window`() = runTest {
        enqueue("""{"copilot_plan":"free","monthly_quotas":{"chat":50,"completions":2000}}""")

        assertTrue(repository.fetchQuota() is Result.Failure)
    }

    @Test
    fun `unfamiliar snapshot keys still yield a quota`() = runTest {
        enqueue(
            """{"copilot_plan":"business","quota_snapshots":{
                "premium_interactions":{"entitlement":0,"remaining":0,"percent_remaining":100.0},
                "broken":"not-an-object",
                "premium_models":{"entitlement":100,"remaining":"40"},
                "team_chat":{"percent_remaining":90.0}}}"""
        )

        val quota = (repository.fetchQuota() as Result.Success).value

        assertEquals(listOf("Premium requests", "Chat"), quota.windows.map { it.label })
        assertEquals(0.60, quota.windows[0].utilization, 1e-9)
        assertEquals(0.10, quota.windows[1].utilization, 1e-9)
    }

    @Test
    fun `a single unknown snapshot is shown rather than failing`() = runTest {
        enqueue("""{"copilot_plan":"pro","quota_snapshots":{"mystery":{"percent_remaining":25.0}}}""")

        val quota = (repository.fetchQuota() as Result.Success).value

        assertEquals(0.75, quota.windows.single().utilization, 1e-9)
    }

    @Test
    fun `sends the GitHub token with editor headers`() = runTest {
        enqueue("""{"copilot_plan":"pro","token_based_billing":true}""")

        repository.fetchQuota()

        val request = server.takeRequest()
        assertEquals("/copilot_internal/user", request.path)
        assertEquals("token gho_token", request.getHeader("Authorization"))
        assertEquals("vscode/1.96.2", request.getHeader("Editor-Version"))
    }

    @Test
    fun `token billed seat without quota shows the plan only`() = runTest {
        enqueue("""{"copilot_plan":"business","token_based_billing":true,"quota_snapshots":{}}""")

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        assertTrue((result as Result.Success).value.windows.isEmpty())
        assertEquals("Business", result.value.tier)
    }

    @Test
    fun `all zero placeholder snapshot is dropped`() = runTest {
        enqueue(
            """{"copilot_plan":"business","quota_snapshots":{
                "premium_interactions":{"entitlement":0,"remaining":0,"percent_remaining":100.0,"unlimited":false}}}"""
        )

        val result = repository.fetchQuota()

        assertTrue(result is Result.Failure)
    }

    @Test
    fun `ended subscription is reported`() = runTest {
        enqueue("""{"access_type_sku":"subscription_ended"}""")

        val result = repository.fetchQuota()

        assertTrue((result as Result.Failure).error is AppError.ParseError)
    }

    @Test
    fun `401 is a terminal auth error`() = runTest {
        enqueue("{}", code = 401)

        val error = (repository.fetchQuota() as Result.Failure).error

        assertTrue(error is AppError.AuthError && error.isTerminal)
    }

    @Test
    fun `missing token is CredentialNotFound`() = runTest {
        `when`(prefsManager.loadCredential(AiService.COPILOT)).thenReturn(null)

        val error = (repository.fetchQuota() as Result.Failure).error

        assertTrue(error is AppError.CredentialNotFound)
    }

    @Test
    fun `reset date accepts ISO instants and plain dates`() {
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), CopilotRepositoryImpl.parseResetDate("2026-12-01"))
        assertEquals(Instant.parse("2026-12-01T08:30:00Z"), CopilotRepositoryImpl.parseResetDate("2026-12-01T08:30:00Z"))
        assertNull(CopilotRepositoryImpl.parseResetDate("soon"))
    }
}
