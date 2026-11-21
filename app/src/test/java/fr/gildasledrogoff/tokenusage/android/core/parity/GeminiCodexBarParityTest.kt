package fr.gildasledrogoff.tokenusage.core.parity

import fr.gildasledrogoff.tokenusage.core.data.GeminiRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiApiService
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiDto
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiProjectsService
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class GeminiCodexBarParityTest {
    private lateinit var env: ParityServer
    private lateinit var prefs: EncryptedPrefsManager
    private lateinit var repository: GeminiRepositoryImpl
    private val seenBodies = mutableMapOf<String, String>()

    private val sampleQuota = """
        {"buckets":[
          {"modelId":"gemini-2.5-pro","remainingFraction":0.6,"resetTime":"2025-01-01T00:00:00Z"},
          {"modelId":"gemini-2.5-flash","remainingFraction":0.9,"resetTime":"2025-01-01T00:00:00Z"},
          {"modelId":"gemini-2.5-flash-lite","remainingFraction":0.8,"resetTime":"2025-01-01T00:00:00Z"}]}
    """
    private val consumerTierDeprecationBody = """
        {"error":{"code":403,"message":"IneligibleTierError / UNSUPPORTED_CLIENT: This client is no longer supported for Gemini Code Assist for individuals. To continue using Gemini, please migrate to the Antigravity suite of products.","status":"PERMISSION_DENIED"}}
    """
    private val quotaSubscriptionRequired = """
        {"error":{"code":403,"message":"You do not have a valid license of this product. Please contact your administrator to request a license. (#3501)","status":"PERMISSION_DENIED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"SUBSCRIPTION_REQUIRED","domain":"cloudaicompanion.googleapis.com"}]}}
    """

    private fun loadCodeAssist(tierId: String?, projectId: String? = null, paidTierName: String? = null): String {
        val parts = buildList {
            if (tierId != null) add(""""currentTier":{"id":"$tierId","name":"${tierId.replace("-tier", "")}"}""")
            if (projectId != null) add(""""cloudaicompanionProject":"$projectId"""")
            if (paidTierName != null) add(""""paidTier":{"name":"$paidTierName"}""")
        }
        return "{" + parts.joinToString(",") + "}"
    }

    private fun unsupportedClient(currentTierId: String? = null, paidTierName: String? = null): String {
        val extra = buildList {
            if (currentTierId != null) add(""""currentTier":{"id":"$currentTierId","name":"$currentTierId"}""")
            if (paidTierName != null) add(""""paidTier":{"name":"$paidTierName"}""")
        }
        return """{"allowedTiers":[{"id":"standard-tier","name":"Gemini Code Assist","userDefinedCloudaicompanionProject":true,"isDefault":true}],
          "ineligibleTiers":[{"reasonCode":"UNSUPPORTED_CLIENT",
          "reasonMessage":"This client is no longer supported for Gemini Code Assist for individuals. To continue using Gemini, please migrate to the Antigravity suite of products: https://antigravity.google",
          "tierId":"free-tier","tierName":"Gemini Code Assist for individuals"}]
          ${if (extra.isNotEmpty()) "," + extra.joinToString(",") else ""}}"""
    }

    @Before
    fun setUp() {
        env = ParityServer()
        prefs = mock(EncryptedPrefsManager::class.java)
        repository = GeminiRepositoryImpl(
            env.service(GeminiApiService::class.java),
            env.service(GeminiProjectsService::class.java),
            env.service(GeminiTokenRefreshService::class.java),
            prefs
        )
        useCredential()
    }

    @After
    fun tearDown() = env.close()

    private fun useCredential(hostedDomain: String? = null) {
        `when`(prefs.getAccounts(AiService.GEMINI)).thenReturn(
            listOf(
                ServiceAccount(
                    "gemini-1", "",
                    Credential.GeminiCredential(
                        accessToken = "token", refreshToken = "refresh",
                        expiresAtMs = System.currentTimeMillis() + 3_600_000,
                        oauthClientId = "cid", oauthClientSecret = "secret",
                        hostedDomain = hostedDomain
                    )
                )
            )
        )
    }

    private fun route(
        loadCodeAssist: Pair<Int, String>? = null,
        quota: Pair<Int, String>? = null,
        projects: Pair<Int, String> = 200 to """{"projects":[]}"""
    ) {
        env.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seenBodies[request.path.orEmpty()] = request.body.readUtf8()
                val (code, body) = when (request.path) {
                    "/v1internal:loadCodeAssist" -> loadCodeAssist ?: (404 to "")
                    "/v1internal:retrieveUserQuota" -> quota ?: (404 to "")
                    "/v1/projects" -> projects
                    else -> 404 to ""
                }
                return MockResponse().setResponseCode(code).setBody(body)
            }
        }
    }

    private suspend fun fetch(): Result<QuotaInfo, AppError> = repository.fetchQuota()

    private suspend fun quota(): QuotaInfo {
        val result = fetch()
        assertTrue("Succès attendu, obtenu : $result", result is Result.Success)
        return (result as Result.Success).value
    }

    private fun QuotaInfo.window(label: String) = windows.firstOrNull { it.label == label }

    @Test
    fun `pro flash and flash lite are separate windows`() = runTest {
        route(loadCodeAssist = 200 to loadCodeAssist("standard-tier"), quota = 200 to sampleQuota)
        val q = quota()
        assertEquals(0.4, q.window("Pro")!!.utilization, 1e-9)
        assertEquals(0.1, q.window("Flash")!!.utilization, 1e-9)
        assertEquals(0.2, q.window("Flash Lite")!!.utilization, 1e-9)
    }

    @Test
    fun `flash only account has no pro window`() = runTest {
        route(
            loadCodeAssist = 200 to loadCodeAssist("free-tier"),
            quota = 200 to """{"buckets":[
              {"modelId":"gemini-2.5-flash","remainingFraction":0.05},
              {"modelId":"gemini-2.5-flash-lite","remainingFraction":0.6}]}"""
        )
        val q = quota()
        assertNull(q.window("Pro"))
        assertEquals(0.95, q.window("Flash")!!.utilization, 1e-9)
        assertEquals(0.40, q.window("Flash Lite")!!.utilization, 1e-9)
    }

    @Test
    fun `lowest remaining fraction wins per family`() = runTest {
        route(
            loadCodeAssist = 200 to loadCodeAssist("free-tier"),
            quota = 200 to """{"buckets":[
              {"modelId":"gemini-2.5-flash","remainingFraction":0.9,"resetTime":"2025-01-01T00:00:00Z"},
              {"modelId":"gemini-2.5-flash","remainingFraction":0.4,"resetTime":"2025-01-01T00:00:00Z"}]}"""
        )
        assertEquals(0.6, quota().window("Flash")!!.utilization, 1e-9)
    }

    @Test
    fun `quota request uses the code assist project`() = runTest {
        route(
            loadCodeAssist = 200 to loadCodeAssist("free-tier", projectId = "managed-project-123"),
            quota = 200 to sampleQuota,
            projects = 500 to ""
        )
        quota()
        assertTrue(seenBodies["/v1internal:retrieveUserQuota"]!!.contains("managed-project-123"))
    }

    @Test
    fun `standard tier is paid`() = runTest {
        route(loadCodeAssist = 200 to loadCodeAssist("standard-tier"), quota = 200 to sampleQuota)
        assertEquals("Paid", quota().tier)
    }

    @Test
    fun `free tier without hosted domain is free`() = runTest {
        route(loadCodeAssist = 200 to loadCodeAssist("free-tier"), quota = 200 to sampleQuota)
        assertEquals("Free", quota().tier)
    }

    @Test
    fun `free tier with hosted domain is workspace`() = runTest {
        useCredential(hostedDomain = "example.com")
        route(loadCodeAssist = 200 to loadCodeAssist("free-tier"), quota = 200 to sampleQuota)
        assertEquals("Workspace", quota().tier)
    }

    @Test
    fun `legacy tier`() = runTest {
        route(loadCodeAssist = 200 to loadCodeAssist("legacy-tier"), quota = 200 to sampleQuota)
        assertEquals("Legacy", quota().tier)
    }

    @Test
    fun `paid tier name overrides workspace`() = runTest {
        useCredential(hostedDomain = "example.com")
        route(loadCodeAssist = 200 to loadCodeAssist("free-tier", paidTierName = "Plus"), quota = 200 to sampleQuota)
        assertEquals("Plus", quota().tier)
    }

    @Test
    fun `paid tier name survives unknown current tier`() = runTest {
        route(
            loadCodeAssist = 200 to loadCodeAssist("future-tier", paidTierName = "Gemini Code Assist in Google One AI Pro"),
            quota = 200 to sampleQuota
        )
        assertEquals("Gemini Code Assist in Google One AI Pro", quota().tier)
    }

    @Test
    fun `paid tier name survives missing current tier`() = runTest {
        route(loadCodeAssist = 200 to loadCodeAssist(null, paidTierName = "Plus"), quota = 200 to sampleQuota)
        assertEquals("Plus", quota().tier)
    }

    @Test
    fun `plan is blank when load code assist fails`() = runTest {
        route(loadCodeAssist = 500 to "", quota = 200 to sampleQuota)
        assertNull(quota().tier)
    }

    @Test
    fun `quota 500 is an http error`() = runTest {
        route(quota = 500 to "")
        val result = fetch()
        assertTrue(result is Result.Failure)
        assertEquals("HTTP 500", ((result as Result.Failure).error as AppError.NetworkError).message)
    }

    @Test
    fun `quota 401 is an authentication failure`() = runTest {
        route(quota = 401 to "")
        val result = fetch()
        assertTrue(result is Result.Failure && result.error is AppError.AuthError)
    }

    @Test
    fun `empty buckets is a parse error`() = runTest {
        route(quota = 200 to """{"buckets":[]}""")
        val result = fetch()
        assertTrue(result is Result.Failure && result.error is AppError.ParseError)
    }

    @Test
    fun `consumer tier deprecation signals`() {
        for (signal in listOf(
            "UNSUPPORTED_CLIENT",
            "IneligibleTierError",
            "no longer supported for Gemini Code Assist for individuals",
            "please migrate Gemini to the Antigravity suite"
        )) assertTrue(signal, GeminiDto.isConsumerTierDeprecationSignal(signal))
        for (signal in listOf("UNAUTHENTICATED", "HTTP 500", "quota bucket missing"))
            assertFalse(signal, GeminiDto.isConsumerTierDeprecationSignal(signal))
    }

    @Test
    fun `deprecation reported from load code assist 403`() = runTest {
        route(loadCodeAssist = 403 to consumerTierDeprecationBody)
        val result = fetch()
        assertTrue(result is Result.Failure && result.error is AppError.ClientUnsupported)
    }

    @Test
    fun `deprecation reported from 200 ineligible tiers`() = runTest {
        route(loadCodeAssist = 200 to unsupportedClient(), quota = 403 to quotaSubscriptionRequired)
        val result = fetch()
        assertTrue(result is Result.Failure && result.error is AppError.ClientUnsupported)
    }

    @Test
    fun `quota 403 after unsupported client signal is deprecation`() = runTest {
        route(loadCodeAssist = 200 to unsupportedClient(currentTierId = "free-tier"), quota = 403 to quotaSubscriptionRequired)
        val result = fetch()
        assertTrue(result is Result.Failure && result.error is AppError.ClientUnsupported)
    }

    // --- GeminiConsumerTierMigrationTests › `keeps licensed tier accounts despite ineligible free tier listing`
    @Test
    fun `licensed tier is kept despite ineligible free tier listing`() = runTest {
        useCredential(hostedDomain = "example.com")
        route(loadCodeAssist = 200 to unsupportedClient(currentTierId = "standard-tier"), quota = 200 to sampleQuota)
        assertEquals("Paid", quota().tier)
    }

    @Test
    fun `plain 403 without signal stays an http error`() = runTest {
        route(loadCodeAssist = 200 to loadCodeAssist("standard-tier"), quota = 403 to quotaSubscriptionRequired)
        val result = fetch()
        assertEquals("HTTP 403", ((result as Result.Failure).error as AppError.NetworkError).message)
    }

    // --- GeminiConsumerTierMigrationTests › `keeps http 403 for licensed tier despite unsupported client listing`
    @Test
    fun `403 for licensed tier stays an http error`() = runTest {
        useCredential(hostedDomain = "example.com")
        route(loadCodeAssist = 200 to unsupportedClient(currentTierId = "standard-tier"), quota = 403 to quotaSubscriptionRequired)
        val result = fetch()
        assertEquals("HTTP 403", ((result as Result.Failure).error as AppError.NetworkError).message)
    }

    @Test
    fun `named paid tier without current tier is not shutdown`() = runTest {
        route(loadCodeAssist = 200 to unsupportedClient(paidTierName = "Plus"), quota = 200 to sampleQuota)
        assertEquals("Plus", quota().tier)
    }

    @Test
    fun `named paid tier 403 stays an http error`() = runTest {
        route(loadCodeAssist = 200 to unsupportedClient(paidTierName = "Plus"), quota = 403 to quotaSubscriptionRequired)
        val result = fetch()
        assertEquals("HTTP 403", ((result as Result.Failure).error as AppError.NetworkError).message)
    }

    @Test
    fun `workspace account without current tier is not shutdown`() = runTest {
        useCredential(hostedDomain = "example.com")
        route(loadCodeAssist = 200 to unsupportedClient(), quota = 200 to sampleQuota)
        assertTrue(quota().windows.isNotEmpty())
    }

    @Test
    fun `workspace free tier 403 stays an http error`() = runTest {
        useCredential(hostedDomain = "example.com")
        route(loadCodeAssist = 200 to unsupportedClient(currentTierId = "free-tier"), quota = 403 to quotaSubscriptionRequired)
        val result = fetch()
        assertEquals("HTTP 403", ((result as Result.Failure).error as AppError.NetworkError).message)
    }
}
