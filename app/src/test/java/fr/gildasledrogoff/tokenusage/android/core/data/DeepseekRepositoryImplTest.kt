package fr.gildasledrogoff.tokenusage.core.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.DeepseekAccount
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
        `when`(prefsManager.getDeepseekAccounts()).thenReturn(listOf(account1, account2))
        fakeHistoryRepository = FakeBalanceHistoryRepository()

        repository = DeepseekRepositoryImpl(apiService, prefsManager, fakeHistoryRepository)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `fetchQuota fetches every account and parses balances`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(balanceBody()))
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                balanceBody(currency = "USD", total = "3.20", granted = "0.00", toppedUp = "3.20")
            )
        )

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        assertEquals(AiService.DEEPSEEK, quotaInfo.service)
        assertTrue(quotaInfo.windows.isEmpty())
        assertEquals(2, quotaInfo.accountBalances.size)
        assertTrue(quotaInfo.accountBalances.all { it.error == null })

        val cny = quotaInfo.accountBalances.mapNotNull { it.balance }.first { it.currency == "CNY" }
        assertEquals(110.0, cny.totalBalance, 0.001)
        assertEquals(10.0, cny.grantedBalance!!, 0.001)
        assertEquals(100.0, cny.toppedUpBalance!!, 0.001)
        assertTrue(cny.isAvailable)

        val usd = quotaInfo.accountBalances.mapNotNull { it.balance }.first { it.currency == "USD" }
        assertEquals(3.20, usd.totalBalance, 0.001)
        assertEquals(0.0, usd.grantedBalance!!, 0.001)
        assertEquals(3.20, usd.toppedUpBalance!!, 0.001)
    }

    @Test
    fun `fetchQuota sends bearer token per account`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(balanceBody()))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(balanceBody()))

        repository.fetchQuota()

        val authHeaders = (1..2).map { mockWebServer.takeRequest() }
        authHeaders.forEach { request ->
            assertEquals("/user/balance", request.path)
        }
        assertEquals(
            setOf("Bearer sk-key-1", "Bearer sk-key-2"),
            authHeaders.map { it.getHeader("Authorization") }.toSet()
        )
    }

    @Test
    fun `fetchQuota keeps other accounts when one fails auth`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(balanceBody()))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val quotaInfo = (result as Result.Success).value
        assertEquals(2, quotaInfo.accountBalances.size)
        val failed = quotaInfo.accountBalances.filter { it.balance == null }
        assertEquals(1, failed.size)
        assertTrue(failed.single().error is AppError.AuthError)
        val ok = quotaInfo.accountBalances.filter { it.balance != null }
        assertEquals(1, ok.size)
    }

    @Test
    fun `fetchQuota maps unavailable balance`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody(balanceBody(isAvailable = false))
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(balanceBody()))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val accounts = (result as Result.Success).value.accountBalances
        assertEquals(2, accounts.count { it.balance != null })
        assertEquals(1, accounts.count { it.balance?.isAvailable == false })
        assertEquals(1, accounts.count { it.balance?.isAvailable == true })
    }

    @Test
    fun `fetchQuota returns ParseError when balance_infos is empty`() = runTest {
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"is_available": true, "balance_infos": []}""")
        )
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(balanceBody()))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val accounts = (result as Result.Success).value.accountBalances
        assertEquals(1, accounts.count { it.error is AppError.ParseError })
        assertEquals(1, accounts.count { it.balance != null })
    }

    @Test
    fun `fetchQuota returns RateLimited on 429`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(429))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(balanceBody()))

        val result = repository.fetchQuota()

        assertTrue(result is Result.Success)
        val accounts = (result as Result.Success).value.accountBalances
        assertEquals(1, accounts.count { it.error is AppError.RateLimited })
        assertEquals(1, accounts.count { it.balance != null })
    }

    @Test
    fun `fetchQuota returns CredentialNotFound when no account configured`() = runTest {
        `when`(prefsManager.getDeepseekAccounts()).thenReturn(emptyList())

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

        repository.fetchQuota()

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
