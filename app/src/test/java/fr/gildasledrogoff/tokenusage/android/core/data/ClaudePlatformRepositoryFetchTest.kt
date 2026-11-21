package fr.gildasledrogoff.tokenusage.core.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.BalanceSample
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionPoint
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.network.claudeplatform.ClaudePlatformApiService
import fr.gildasledrogoff.tokenusage.core.network.claudeplatform.ClaudePlatformDto
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
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
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import retrofit2.Retrofit
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ClaudePlatformRepositoryFetchTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: ClaudePlatformRepositoryImpl
    private val routes = mutableMapOf<String, MockResponse>()
    private val history = RecordingHistory()
    private val now = Instant.parse("2026-11-21T12:00:00Z")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val account = ServiceAccount("acc-1", "Console", Credential.ApiKeyCredential("sk-session"))

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    routes[request.path] ?: MockResponse().setResponseCode(404)
            }
            start()
        }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ClaudePlatformApiService::class.java)
        val prefs = mock(EncryptedPrefsManager::class.java)
        `when`(prefs.getAccounts(AiService.CLAUDE_PLATFORM)).thenReturn(listOf(account))
        repository = ClaudePlatformRepositoryImpl(api, prefs, Clock.fixed(now, ZoneOffset.UTC), history)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun route(path: String, body: String, code: Int = 200) {
        routes[path] = MockResponse().setResponseCode(code).setBody(body)
    }

    private fun apiOrg(id: String, name: String = "Org $id") =
        """{"uuid":"$id","name":" $name ","capabilities":["api"]}"""

    private fun failure(result: Result<*, AppError>) = (result as Result.Failure).error

    @Test
    fun `a blank cookie means no credential`() = runTest {
        val blank = ServiceAccount("x", "", Credential.ApiKeyCredential("  "))

        assertEquals(AppError.CredentialNotFound(AiService.CLAUDE_PLATFORM), failure(repository.fetchQuota(blank)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the session cookie is sent to the organization endpoint`() = runTest {
        route("/api/organizations", "[]")

        repository.fetchQuota(account)

        assertEquals("sessionKey=sk-session", server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun `organization auth errors are mapped`() = runTest {
        route("/api/organizations", "", code = 401)

        assertTrue(failure(repository.fetchQuota(account)) is AppError.AuthError)
    }

    @Test
    fun `organization outage is reported as unavailable`() = runTest {
        route("/api/organizations", "", code = 502)

        assertEquals(AppError.ServiceUnavailable, failure(repository.fetchQuota(account)))
    }

    @Test
    fun `no api organization is a parse error`() = runTest {
        route(
            "/api/organizations",
            """[{"uuid":"chat-only","capabilities":["chat"]},{"uuid":"bad id!","capabilities":["api"]},{"capabilities":["api"]}]"""
        )

        assertTrue(failure(repository.fetchQuota(account)) is AppError.ParseError)
    }

    @Test
    fun `a successful fetch builds the quota and records the balance`() = runTest {
        route("/api/organizations", "[${apiOrg("org-1", "Acme")}]")
        route("/api/organizations/org-1/prepaid/credits", """{"amount":2500,"currency":"eur"}""")
        route("/api/organizations/org-1/current_spend", """{"amount":150}""")

        val quota = (repository.fetchQuota(account) as Result.Success).value

        assertEquals("Acme", quota.tier)
        assertEquals("acc-1", quota.accountId)
        assertEquals("Console", quota.accountLabel)
        assertEquals(now, quota.fetchedAt)
        val balance = quota.accountBalances.single().balance!!
        assertEquals("EUR", balance.currency)
        assertEquals(25.0, balance.totalBalance, 1e-9)
        assertEquals(SpendPeriod.THIS_MONTH, quota.spending.single().period)
        assertEquals(1.5, quota.spending.single().amount, 1e-9)
        assertEquals(listOf(RecordingHistory.Record("acc-1", "Console", "EUR", 25.0)), history.records)
    }

    @Test
    fun `a forbidden organization is skipped for the next one`() = runTest {
        route("/api/organizations", "[${apiOrg("org-1")},${apiOrg("org-2", "Second")}]")
        route("/api/organizations/org-1/prepaid/credits", "", code = 403)
        route("/api/organizations/org-2/prepaid/credits", """{"amount":100}""")

        val quota = (repository.fetchQuota(account) as Result.Success).value

        assertEquals("Second", quota.tier)
    }

    @Test
    fun `every organization forbidden means billing access denied`() = runTest {
        route("/api/organizations", "[${apiOrg("org-1")}]")
        route("/api/organizations/org-1/prepaid/credits", "", code = 403)

        val error = failure(repository.fetchQuota(account)) as AppError.AuthError

        assertEquals("Billing access denied", error.message)
        assertEquals(false, error.isTerminal)
    }

    @Test
    fun `a credit error stops the fetch`() = runTest {
        route("/api/organizations", "[${apiOrg("org-1")}]")
        route("/api/organizations/org-1/prepaid/credits", "", code = 429)

        assertEquals(AppError.RateLimited, failure(repository.fetchQuota(account)))
    }

    @Test
    fun `credits without amount are malformed`() = runTest {
        route("/api/organizations", "[${apiOrg("org-1")}]")
        route("/api/organizations/org-1/prepaid/credits", """{"currency":"USD"}""")

        val error = failure(repository.fetchQuota(account)) as AppError.ParseError

        assertEquals("Malformed Claude Console credit balance", error.message)
        assertTrue(history.records.isEmpty())
    }

    @Test
    fun `spend failure or negative spend is omitted`() = runTest {
        route("/api/organizations", "[${apiOrg("org-1")}]")
        route("/api/organizations/org-1/prepaid/credits", """{"amount":100}""")
        route("/api/organizations/org-1/current_spend", """{"amount":-5}""")

        assertTrue((repository.fetchQuota(account) as Result.Success).value.spending.isEmpty())

        route("/api/organizations/org-1/current_spend", "", code = 500)

        assertTrue((repository.fetchQuota(account) as Result.Success).value.spending.isEmpty())
    }

    @Test
    fun `a history failure does not fail the fetch`() = runTest {
        history.fail = true
        route("/api/organizations", "[${apiOrg("org-1")}]")
        route("/api/organizations/org-1/prepaid/credits", """{"amount":100}""")

        assertTrue(repository.fetchQuota(account) is Result.Success)
    }

    @Test
    fun `invalid currency and exponent fall back to defaults`() {
        val quota = ClaudePlatformRepositoryImpl.toQuotaInfo(
            ClaudePlatformDto.Credits(
                amount = -10,
                currency = "euro",
                balance = ClaudePlatformDto.Balance(ClaudePlatformDto.BalanceCredits(exponent = 9))
            ),
            spendMinor = 250,
            organization = "",
            now = now
        )

        val balance = quota.accountBalances.single().balance!!
        assertEquals("USD", balance.currency)
        assertEquals(0.0, balance.totalBalance, 1e-9)
        assertEquals(false, balance.isAvailable)
        assertEquals(2.5, quota.spending.single().amount, 1e-9)
        assertEquals(null, quota.tier)
    }

    @Test
    fun `a custom exponent scales every amount`() {
        val quota = ClaudePlatformRepositoryImpl.toQuotaInfo(
            ClaudePlatformDto.Credits(
                amount = 12345,
                balance = ClaudePlatformDto.Balance(ClaudePlatformDto.BalanceCredits(exponent = 3)),
                tranches = listOf(ClaudePlatformDto.Tranche(granted = 20000, remaining = 5000))
            ),
            spendMinor = null,
            organization = null,
            now = now
        )

        assertEquals(12.345, quota.accountBalances.single().balance!!.totalBalance, 1e-9)
        assertEquals(0.75, quota.windows.single().utilization, 1e-9)
    }

    @Test
    fun `empty and expired grants are ignored and an exhausted purchase is still shown`() {
        val quota = ClaudePlatformRepositoryImpl.toQuotaInfo(
            ClaudePlatformDto.Credits(
                amount = 0,
                promoTranches = listOf(
                    ClaudePlatformDto.Tranche(programId = "subscription_api_credit", granted = 0, remaining = 0),
                    ClaudePlatformDto.Tranche(
                        programId = "subscription_api_credit",
                        granted = 100,
                        remaining = 100,
                        expiresAt = "2026-11-01T00:00:00Z"
                    ),
                    ClaudePlatformDto.Tranche(
                        programId = "subscription_api_credit",
                        granted = 100,
                        remaining = 40,
                        expiresAt = "not a date"
                    )
                ),
                tranches = listOf(ClaudePlatformDto.Tranche(granted = 100, remaining = -3))
            ),
            spendMinor = null,
            organization = null,
            now = now
        )

        assertEquals(listOf("API credit", "Purchased credit"), quota.windows.map { it.label })
        assertEquals(0.6, quota.windows[0].utilization, 1e-9)
        assertEquals(null, quota.windows[0].resetsAt)
        assertEquals(1.0, quota.windows[1].utilization, 1e-9)
    }

    private class RecordingHistory : BalanceHistoryRepository {
        data class Record(val accountId: String, val label: String, val currency: String, val total: Double)

        val records = mutableListOf<Record>()
        var fail = false

        override suspend fun record(
            service: AiService,
            accountId: String,
            accountLabel: String,
            currency: String,
            totalBalance: Double
        ) {
            if (fail) throw IllegalStateException("db closed")
            records += Record(accountId, accountLabel, currency, totalBalance)
        }

        override suspend fun latestSample(service: AiService, accountId: String): BalanceSample? = null

        override suspend fun consumption(
            service: AiService,
            accountId: String,
            granularity: ConsumptionGranularity
        ): List<ConsumptionPoint> = emptyList()
    }
}
