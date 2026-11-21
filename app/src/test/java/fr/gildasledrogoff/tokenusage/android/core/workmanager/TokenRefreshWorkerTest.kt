package fr.gildasledrogoff.tokenusage.core.workmanager

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import fr.gildasledrogoff.tokenusage.core.diagnostics.DiagnosticsStore
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.network.codex.CodexTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.security.InMemorySharedPreferences
import fr.gildasledrogoff.tokenusage.core.widget.WidgetPrefsManager
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import fr.gildasledrogoff.tokenusage.feature.settings.inMemoryPrefsManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import java.time.Clock
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class TokenRefreshWorkerTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var prefs: EncryptedPrefsManager
    private lateinit var widgetPrefs: WidgetPrefsManager
    private lateinit var diagnostics: DiagnosticsStore
    private val routes = mutableMapOf<String, MockResponse>()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    private val noRates = object : ExchangeRateRepository {
        override suspend fun displayRate(currency: String): FxRate? = null
        override suspend fun fetchLatest(source: FxRateSource, from: String, to: String): Result<FxRate, AppError> =
            error("unused")

        override fun cachedRate(source: FxRateSource, from: String, to: String): FxRate? = null
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    routes[request.path] ?: MockResponse().setResponseCode(500)
            }
            start()
        }
        prefs = inMemoryPrefsManager()
        widgetPrefs = WidgetPrefsManager(context)
        diagnostics = DiagnosticsStore(lazy { InMemorySharedPreferences() }, Clock.systemUTC())
    }

    @After
    fun tearDown() = server.shutdown()

    private inline fun <reified T> api(): T = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(T::class.java)

    private fun worker(attempt: Int = 0): TokenRefreshWorker =
        TestListenableWorkerBuilder<TokenRefreshWorker>(context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker = TokenRefreshWorker(
                    appContext,
                    workerParameters,
                    api<ClaudeTokenRefreshService>(),
                    api<CodexTokenRefreshService>(),
                    api<GeminiTokenRefreshService>(),
                    prefs,
                    widgetPrefs,
                    WidgetQuotaPublisher(appContext, widgetPrefs, noRates),
                    diagnostics
                )
            })
            .build()

    private fun route(path: String, body: String, code: Int = 200) {
        routes[path] = MockResponse().setResponseCode(code).setBody(body)
    }

    private fun lastRun() = diagnostics.workerRuns().single()

    @Test
    fun `no account succeeds and records the run`() = runTest {
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(WorkManagerInitializer.TOKEN_WORK_NAME, lastRun().workName)
        assertEquals("success", lastRun().result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `accounts without refresh flow are left alone`() = runTest {
        prefs.addAccount(AiService.DEEPSEEK, "", Credential.DeepseekCredential("sk"))
        prefs.addAccount(AiService.OPENAI, "", Credential.ApiKeyCredential("sk"))
        prefs.addAccount(AiService.CLAUDE, "", Credential.ClaudeCredential("at", "rt", expiresAt = null))

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a claude token far from expiry is not refreshed`() = runTest {
        prefs.addAccount(
            AiService.CLAUDE,
            "",
            Credential.ClaudeCredential("at", "rt", expiresAt = Instant.now().plusSeconds(3600))
        )

        worker().doWork()

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an expiring claude token is refreshed and keeps its metadata`() = runTest {
        val account = prefs.addAccount(
            AiService.CLAUDE,
            "Work",
            Credential.ClaudeCredential("old", "rt", Instant.now().plusSeconds(60), "user:inference", "max")
        )
        route("/v1/oauth/token", """{"access_token":"new","expires_in":3600}""")

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        val stored = prefs.getAccounts(AiService.CLAUDE).single { it.id == account.id }.credential
            as Credential.ClaudeCredential
        assertEquals("new", stored.accessToken)
        assertEquals("rt", stored.refreshToken)
        assertEquals("user:inference", stored.scopes)
        assertEquals("max", stored.rateLimitTier)
        assertTrue(stored.expiresAt!!.isAfter(Instant.now().plusSeconds(3000)))
    }

    @Test
    fun `an expired claude token without refresh token is retried`() = runTest {
        prefs.addAccount(
            AiService.CLAUDE,
            "",
            Credential.ClaudeCredential("at", null, expiresAt = Instant.now().minusSeconds(1))
        )

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        assertEquals("retry", lastRun().result)
    }

    @Test
    fun `a failed refresh gives up after the last attempt`() = runTest {
        prefs.addAccount(
            AiService.CLAUDE,
            "",
            Credential.ClaudeCredential("at", "rt", expiresAt = Instant.now().minusSeconds(1))
        )
        route("/v1/oauth/token", "", code = 500)

        assertEquals(
            ListenableWorker.Result.success(),
            worker(attempt = WorkRetryPolicy.MAX_ATTEMPTS - 1).doWork()
        )
        assertEquals("gave_up", lastRun().result)
    }

    @Test
    fun `codex tokens are always refreshed`() = runTest {
        prefs.addAccount(AiService.CODEX, "", Credential.CodexCredential("old", "rt", accountId = "acct"))
        route("/oauth/token", """{"access_token":"new","refresh_token":"rt2"}""")

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertEquals(
            Credential.CodexCredential("new", "rt2", accountId = "acct"),
            prefs.getAccounts(AiService.CODEX).single().credential
        )
    }

    @Test
    fun `a terminal codex error removes the account and its widgets`() = runTest {
        prefs.addAccount(AiService.CODEX, "", Credential.CodexCredential("old", "rt"))
        widgetPrefs.saveSelectedServices(1, setOf(AiService.CODEX, AiService.CLAUDE))
        route("/oauth/token", """{"error":"invalid_grant"}""", code = 400)

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())

        assertTrue(prefs.getAccounts(AiService.CODEX).isEmpty())
        assertEquals(setOf(AiService.CLAUDE), widgetPrefs.getSelectedServices(1))
    }

    @Test
    fun `a transient codex error keeps the account`() = runTest {
        prefs.addAccount(AiService.CODEX, "", Credential.CodexCredential("old", "rt"))
        route("/oauth/token", """{"error":"server_error"}""", code = 500)

        worker().doWork()

        assertEquals(1, prefs.getAccounts(AiService.CODEX).size)
    }

    @Test
    fun `an expiring gemini token is refreshed with its client`() = runTest {
        prefs.addAccount(
            AiService.GEMINI,
            "",
            Credential.GeminiCredential("old", "rt", System.currentTimeMillis(), "cid", "secret", "corp.example")
        )
        route("/token", """{"access_token":"new"}""")

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        val stored = prefs.getAccounts(AiService.GEMINI).single().credential as Credential.GeminiCredential
        assertEquals("new", stored.accessToken)
        assertEquals("rt", stored.refreshToken)
        assertEquals("cid", stored.oauthClientId)
        assertEquals("corp.example", stored.hostedDomain)
        assertTrue(stored.expiresAtMs > System.currentTimeMillis() + 3_000_000)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("client_id=cid") && body.contains("client_secret=secret"))
    }

    @Test
    fun `a fresh gemini token is not refreshed`() = runTest {
        prefs.addAccount(
            AiService.GEMINI,
            "",
            Credential.GeminiCredential("at", "rt", System.currentTimeMillis() + 3_600_000, "cid", "secret")
        )

        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(0, server.requestCount)
    }
}
