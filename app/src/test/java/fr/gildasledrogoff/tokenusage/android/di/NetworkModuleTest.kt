package fr.gildasledrogoff.tokenusage.di

import fr.gildasledrogoff.tokenusage.core.network.RetryInterceptor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkModuleTest {
    private val json: Json = NetworkModule.provideJson()
    private val requested = mutableListOf<HttpUrl>()

    private val capturing = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requested += chain.request().url
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private suspend fun hostOf(call: suspend () -> Unit): String {
        runCatching { call() }
        return requested.last().host
    }

    @Test
    fun `json is lenient and ignores unknown keys`() {
        assertTrue(json.configuration.ignoreUnknownKeys)
        assertTrue(json.configuration.coerceInputValues)
        assertTrue(json.configuration.isLenient)
    }

    @Test
    fun `provider clients retry rate limits and use standard timeouts`() {
        listOf(
            NetworkModule.provideClaudeOkHttpClient(),
            NetworkModule.provideClaudeTokenOkHttpClient(),
            NetworkModule.provideClaudeWebOkHttpClient(),
            NetworkModule.provideCodexOkHttpClient(),
            NetworkModule.provideCodexTokenOkHttpClient(),
            NetworkModule.provideGeminiOkHttpClient(),
            NetworkModule.provideGeminiTokenOkHttpClient(),
            NetworkModule.provideDeepseekOkHttpClient(),
            NetworkModule.provideZaiOkHttpClient(),
            NetworkModule.provideCopilotOkHttpClient(),
            NetworkModule.provideOpenRouterOkHttpClient(),
            NetworkModule.provideOpenAiOkHttpClient(),
            NetworkModule.provideClaudePlatformOkHttpClient()
        ).forEach { client ->
            assertTrue(client.interceptors.any { it is RetryInterceptor })
            assertEquals(15_000, client.connectTimeoutMillis)
            assertEquals(30_000, client.readTimeoutMillis)
            assertEquals(30_000, client.writeTimeoutMillis)
        }
    }

    @Test
    fun `each provider gets its own client instance`() {
        assertNotSame(NetworkModule.provideClaudeOkHttpClient(), NetworkModule.provideCodexOkHttpClient())
    }

    @Test
    fun `debug clients log bodies at most once per client`() {
        val client = NetworkModule.provideClaudeOkHttpClient()
        val loggers = client.interceptors.filterIsInstance<HttpLoggingInterceptor>()

        assertTrue(loggers.size <= 1)
        loggers.forEach { assertEquals(HttpLoggingInterceptor.Level.BODY, it.level) }
    }

    @Test
    fun `exchange rate client is short lived and does not retry`() {
        val client = NetworkModule.provideExchangeRateOkHttpClient()

        assertFalse(client.interceptors.any { it is RetryInterceptor })
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(10_000, client.readTimeoutMillis)
        assertEquals(20_000, client.callTimeoutMillis)
    }

    @Test
    fun `services target their provider hosts`() = runTest {
        val claude = NetworkModule.provideClaudeApiService(capturing, json)
        val deepseek = NetworkModule.provideDeepseekApiService(capturing, json)
        val copilot = NetworkModule.provideCopilotApiService(capturing, json)
        val projects = NetworkModule.provideGeminiProjectsService(capturing, json)
        val platform = NetworkModule.provideClaudePlatformApiService(capturing, json)
        val web = NetworkModule.provideClaudeWebApiService(capturing, json)

        assertEquals("api.anthropic.com", hostOf { claude.getUsage("Bearer x") })
        assertEquals("api.deepseek.com", hostOf { deepseek.getUserBalance("Bearer x") })
        assertEquals("api.github.com", hostOf { copilot.getUser("token x") })
        assertEquals("cloudresourcemanager.googleapis.com", hostOf { projects.listProjects("Bearer x") })
        assertEquals("platform.claude.com", hostOf { platform.getOrganizations("sessionKey=x") })
        assertEquals("claude.ai", hostOf { web.getOrganizations("sessionKey=x") })
    }

    @Test
    fun `every service can be created`() {
        listOf<Any>(
            NetworkModule.provideClaudeTokenRefreshService(capturing, json),
            NetworkModule.provideCodexApiService(capturing, json),
            NetworkModule.provideCodexTokenRefreshService(capturing, json),
            NetworkModule.provideGeminiApiService(capturing, json),
            NetworkModule.provideGeminiTokenRefreshService(capturing, json),
            NetworkModule.provideZaiApiService(capturing, json),
            NetworkModule.provideGitHubDeviceFlowService(capturing, json),
            NetworkModule.provideOpenRouterApiService(capturing, json),
            NetworkModule.provideOpenAiApiService(capturing, json)
        ).forEach { assertTrue(java.lang.reflect.Proxy.isProxyClass(it.javaClass)) }
    }
}
