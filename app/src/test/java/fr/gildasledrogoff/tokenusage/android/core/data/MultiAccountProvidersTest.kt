package fr.gildasledrogoff.tokenusage.core.data
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.network.codex.CodexApiService
import fr.gildasledrogoff.tokenusage.core.network.codex.CodexTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.network.copilot.CopilotApiService
import fr.gildasledrogoff.tokenusage.core.network.openrouter.OpenRouterApiService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.security.InMemorySharedPreferences
import fr.gildasledrogoff.tokenusage.core.security.SecureStorageFactory
import fr.gildasledrogoff.tokenusage.core.widget.WidgetPrefsManager
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import retrofit2.Retrofit

class MultiAccountProvidersTest {
    private lateinit var server: MockWebServer
    private lateinit var prefs: EncryptedPrefsManager
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val factory = mock(SecureStorageFactory::class.java)
        `when`(factory.credentials).thenReturn(InMemorySharedPreferences())
        prefs = EncryptedPrefsManager(factory)
    }
    @After
    fun tearDown() = server.shutdown()
    private inline fun <reified T> api(): T = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(OkHttpClient())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(T::class.java)
    
    private fun answerByToken(bodies: Map<String, String>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val token = request.getHeader("Authorization").orEmpty().substringAfter(' ')
                return bodies[token]?.let { MockResponse().setBody(it) } ?: MockResponse().setResponseCode(401)
            }
        }
    }
    @Test
    fun `copilot queries each account with its own token`() = runTest {
        val provider = CopilotRepositoryImpl(api<CopilotApiService>(), prefs)
        prefs.addAccount(AiService.COPILOT, "Perso", Credential.ApiKeyCredential("gho_a"))
        prefs.addAccount(AiService.COPILOT, "", Credential.ApiKeyCredential("gho_b"))
        answerByToken(
            mapOf(
                "gho_a" to """{"copilot_plan":"individual","quota_snapshots":{"premium_interactions":
                    {"entitlement":100,"remaining":80,"percent_remaining":80.0,"unlimited":false}}}""",
                "gho_b" to """{"copilot_plan":"business","quota_snapshots":{"premium_interactions":
                    {"entitlement":100,"remaining":10,"percent_remaining":10.0,"unlimited":false}}}"""
            )
        )
        val accounts = provider.accounts()
        val quotas = accounts.map { (provider.fetchQuota(it) as Result.Success).value }
        assertEquals(listOf("Individual", "Business"), quotas.map { it.tier })
        assertEquals(0.2, quotas[0].windows.single().utilization, 1e-9)
        assertEquals(0.9, quotas[1].windows.single().utilization, 1e-9)
        assertEquals(accounts.map { it.id }, quotas.map { it.accountId })
        assertEquals("Perso", quotas[0].accountLabel)
        assertNull("a blank label is not an identity", quotas[1].accountLabel)
    }
    @Test
    fun `openrouter accounts fail independently`() = runTest {
        val provider = OpenRouterRepositoryImpl(api<OpenRouterApiService>(), prefs)
        prefs.addAccount(AiService.OPENROUTER, "ok", Credential.ApiKeyCredential("good"))
        prefs.addAccount(AiService.OPENROUTER, "revoked", Credential.ApiKeyCredential("bad"))
        answerByToken(
            mapOf(
                "good" to """{"data":{"label":"x","limit":10.0,"limit_reset":"monthly","limit_remaining":5.0,"usage":5.0,
                    "usage_daily":0.1,"usage_weekly":1.0,"usage_monthly":5.0,"is_free_tier":false}}"""
            )
        )
        val (good, bad) = provider.accounts().map { provider.fetchQuota(it) }
        assertEquals(0.5, (good as Result.Success).value.windows.single().utilization, 1e-9)
        val error = (bad as Result.Failure).error
        assertTrue(error is AppError.AuthError && error.isTerminal)
    }
    @Test
    fun `validate targets the requested account only`() = runTest {
        val provider = CopilotRepositoryImpl(api<CopilotApiService>(), prefs)
        prefs.addAccount(AiService.COPILOT, "a", Credential.ApiKeyCredential("good"))
        val bad = prefs.addAccount(AiService.COPILOT, "b", Credential.ApiKeyCredential("bad"))
        answerByToken(mapOf("good" to """{"copilot_plan":"individual","quota_snapshots":{"chat":
            {"entitlement":10,"remaining":5,"percent_remaining":50.0,"unlimited":false}}}"""))
        assertTrue(provider.validateAccount(provider.accounts()[0].id) is Result.Success)
        assertTrue(provider.validateAccount(bad.id) is Result.Failure)
        assertTrue(
            (provider.validateAccount("unknown") as Result.Failure).error is AppError.CredentialNotFound
        )
    }
    @Test
    fun `codex refreshes only the account whose token expired`() = runTest {
        val widgetPrefs = mock(WidgetPrefsManager::class.java)
        val publisher = mock(WidgetQuotaPublisher::class.java)
        val provider = CodexRepositoryImpl(
            api<CodexApiService>(), api<CodexTokenRefreshService>(), prefs, widgetPrefs, publisher
        )
        val expired = prefs.addAccount(AiService.CODEX, "old", Credential.CodexCredential("at-old", "rt-old", null))
        val healthy = prefs.addAccount(AiService.CODEX, "fresh", Credential.CodexCredential("at-ok", "rt-ok", null))
        val usage = """{"plan_type":"plus","rate_limit":{"primary_window":
            {"used_percent":10,"reset_at":1234567890,"limit_window_seconds":18000}}}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val auth = request.getHeader("Authorization").orEmpty()
                return when {
                    request.path!!.contains("oauth/token") ->
                        MockResponse().setBody("""{"access_token":"at-new","refresh_token":"rt-new"}""")
                    auth == "Bearer at-old" -> MockResponse().setResponseCode(401)
                    else -> MockResponse().setBody(usage)
                }
            }
        }
        val results = provider.accounts().map { provider.fetchQuota(it) }
        assertTrue(results.all { it is Result.Success })
        val stored = prefs.getAccounts(AiService.CODEX).associateBy { it.id }
        assertEquals(Credential.CodexCredential("at-new", "rt-new", null), stored.getValue(expired.id).credential)
        assertEquals(Credential.CodexCredential("at-ok", "rt-ok", null), stored.getValue(healthy.id).credential)
    }
    @Test
    fun `codex drops only the revoked account`() = runTest {
        val widgetPrefs = mock(WidgetPrefsManager::class.java)
        val publisher = mock(WidgetQuotaPublisher::class.java)
        val provider = CodexRepositoryImpl(
            api<CodexApiService>(), api<CodexTokenRefreshService>(), prefs, widgetPrefs, publisher
        )
        val revoked = prefs.addAccount(AiService.CODEX, "gone", Credential.CodexCredential("at-1", "rt-1", null))
        val kept = prefs.addAccount(AiService.CODEX, "kept", Credential.CodexCredential("at-2", "rt-2", null))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.path!!.contains("oauth/token") ->
                    MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
                else -> MockResponse().setResponseCode(401)
            }
        }
        val result = provider.fetchQuota(provider.accounts().first { it.id == revoked.id })
        assertTrue((result as Result.Failure).error is AppError.AuthError)
        assertEquals(listOf(kept.id), prefs.getAccounts(AiService.CODEX).map { it.id })
    }
}
