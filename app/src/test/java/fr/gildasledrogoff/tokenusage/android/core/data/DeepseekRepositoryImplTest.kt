package fr.gildasledrogoff.tokenusage.core.data
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.DeepseekAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.toServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.network.deepseek.DeepseekApiService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import retrofit2.Retrofit
class DeepseekRepositoryImplTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiService: DeepseekApiService
    private lateinit var prefsManager: EncryptedPrefsManager
    private lateinit var fakeHistoryRepository: FakeBalanceHistoryRepository
    private lateinit var repository: DeepseekRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val account1 = DeepseekAccount(id = "id-1", label = "Personal", apiKey = "sk-key-1")
    private val account2 = DeepseekAccount(id = "id-2", label = "Work", apiKey = "sk-key-2")
    private fun balanceBody(
        currency: String = "CNY",
        total: String = "110.00",
        granted: String = "10.00",
        toppedUp: String = "100.00",
        isAvailable: Boolean = true
    ): String {
        return """
        {
            "is_available": $isAvailable,
            "balance_infos": [
                {
                    "currency": "$currency",
                    "total_balance": "$total",
                    "granted_balance": "$granted",
                    "topped_up_balance": "$toppedUp"
                }
            ]
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
            .create(DeepseekApiService::class.java)
        prefsManager = mock(EncryptedPrefsManager::class.java)
        `when`(prefsManager.getAccounts(AiService.DEEPSEEK)).thenReturn(listOf(account1, account2).map { it.toServiceAccount() })
        fakeHistoryRepository = FakeBalanceHistoryRepository()
        repository = DeepseekRepositoryImpl(apiService, prefsManager, fakeHistoryRepository)
    }
    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }
    private val accounts = listOf(account1, account2).map { it.toServiceAccount() }
    
    private suspend fun fetchEach() = accounts.map { repository.fetchQuota(it) }
    @Test
    fun `fetchQuota parses the balance of each account`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(balanceBody()))
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                balanceBody(currency = "USD", total = "3.20", granted = "0.00", toppedUp = "3.20")
            )
        )
        val (first, second) = fetchEach().map { (it as Result.Success).value }
        assertEquals(AiService.DEEPSEEK, first.service)
        assertTrue(first.windows.isEmpty())
        assertEquals("id-1", first.accountId)
        assertEquals("Personal", first.accountLabel)
        assertEquals("id-2", second.accountId)
        val cny = first.accountBalances.single().balance!!
        assertEquals("CNY", cny.currency)
        assertEquals(110.0, cny.totalBalance, 0.001)
        assertEquals(10.0, cny.grantedBalance!!, 0.001)
        assertEquals(100.0, cny.toppedUpBalance!!, 0.001)
        assertTrue(cny.isAvailable)
        val usd = second.accountBalances.single().balance!!
        assertEquals(3.20, usd.totalBalance, 0.001)
        assertEquals(0.0, usd.grantedBalance!!, 0.001)
        assertEquals(3.20, usd.toppedUpBalance!!, 0.001)
    }
    @Test
    fun `fetchQuota sends the bearer token of the queried account`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(balanceBody()))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(balanceBody()))
        fetchEach()
        val requests = (1..2).map { mockWebServer.takeRequest() }
        requests.forEach { assertEquals("/user/balance", it.path) }
        assertEquals(listOf("Bearer sk-key-1", "Bearer sk-key-2"), requests.map { it.getHeader("Authorization") })
    }
    @Test
    fun `one account failing auth does not affect the others`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(balanceBody()))
        val (failed, ok) = fetchEach()
        val error = (failed as Result.Failure).error
        assertTrue(error is AppError.AuthError)
        assertTrue((error as AppError.AuthError).isTerminal)
        assertTrue(ok is Result.Success)
    }
    @Test
    fun `fetchQuota maps unavailable balance`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(balanceBody(isAvailable = false))
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(balanceBody()))
        val (unavailable, available) = fetchEach().map { (it as Result.Success).value.accountBalances.single().balance!! }
        assertFalse(unavailable.isAvailable)
        assertTrue(available.isAvailable)
    }
    @Test
    fun `fetchQuota returns ParseError when balance_infos is empty`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"is_available": true, "balance_infos": []}""")
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(balanceBody()))
        val (empty, ok) = fetchEach()
        assertTrue((empty as Result.Failure).error is AppError.ParseError)
        assertTrue(ok is Result.Success)
    }
    @Test
    fun `fetchQuota returns RateLimited on 429`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(429))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(balanceBody()))
        val (limited, ok) = fetchEach()
        assertTrue((limited as Result.Failure).error is AppError.RateLimited)
        assertTrue(ok is Result.Success)
    }
    @Test
    fun `fetchQuota without argument uses the first account and reports a missing one`() = runTest {
        `when`(prefsManager.getAccounts(AiService.DEEPSEEK)).thenReturn(emptyList())
        val result = repository.fetchQuota()
        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).error is AppError.CredentialNotFound)
    }
    @Test
    fun `validateAccount returns Success on valid key`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(balanceBody()))
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
    @Test
    fun `fetchQuota records a balance snapshot per account`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(balanceBody()))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(balanceBody()))
        fetchEach()
        assertEquals(2, fakeHistoryRepository.recorded.size)
        assertEquals(setOf("id-1", "id-2"), fakeHistoryRepository.recorded.map { it.accountId }.toSet())
        fakeHistoryRepository.recorded.forEach { record ->
            assertEquals(AiService.DEEPSEEK, record.service)
            assertEquals("CNY", record.currency)
            assertEquals(110.0, record.totalBalance, 0.0001)
        }
    }
    private class FakeBalanceHistoryRepository : BalanceHistoryRepository {
        data class Record(
            val service: AiService,
            val accountId: String,
            val accountLabel: String,
            val currency: String,
            val totalBalance: Double
        )
        val recorded = mutableListOf<Record>()
        override suspend fun record(
            service: AiService,
            accountId: String,
            accountLabel: String,
            currency: String,
            totalBalance: Double
        ) {
            recorded.add(Record(service, accountId, accountLabel, currency, totalBalance))
        }
        override suspend fun latestSample(service: AiService, accountId: String): fr.gildasledrogoff.tokenusage.core.domain.model.BalanceSample? = null
        override suspend fun consumption(
            service: AiService,
            accountId: String,
            granularity: fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
        ): List<fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionPoint> = emptyList()
    }
}
