package fr.gildasledrogoff.tokenusage.core.parity

import fr.gildasledrogoff.tokenusage.core.data.DeepseekRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.CreditBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.network.deepseek.DeepseekApiService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DeepseekCodexBarParityTest {
    private lateinit var env: ParityServer
    private lateinit var repository: DeepseekRepositoryImpl

    @Before
    fun setUp() {
        env = ParityServer()
        val prefs = mock(EncryptedPrefsManager::class.java)
        `when`(prefs.getAccounts(AiService.DEEPSEEK)).thenReturn(
            listOf(ServiceAccount("ds-1", "", Credential.DeepseekCredential("fixture-key")))
        )
        repository = DeepseekRepositoryImpl(
            env.service(DeepseekApiService::class.java), prefs, mock(BalanceHistoryRepository::class.java)
        )
    }

    @After
    fun tearDown() = env.close()

    private suspend fun fetch(json: String): Result<QuotaInfo, AppError> {
        env.enqueue(200, json)
        return repository.fetchQuota()
    }

    private suspend fun balance(json: String): CreditBalance {
        val result = fetch(json)
        assertTrue("Succès attendu, obtenu : $result", result is Result.Success)
        return (result as Result.Success).value.accountBalances.single().balance!!
    }

    private fun info(currency: String, total: String, granted: String, topped: String) =
        """{"currency":"$currency","total_balance":"$total","granted_balance":"$granted","topped_up_balance":"$topped"}"""

    @Test
    fun `parses usd balance`() = runTest {
        val b = balance("""{"is_available":true,"balance_infos":[${info("USD", "50.00", "10.00", "40.00")}]}""")
        assertTrue(b.isAvailable)
        assertEquals("USD", b.currency)
        assertEquals(50.0, b.totalBalance, 0.0)
        assertEquals(10.0, b.grantedBalance!!, 0.0)
        assertEquals(40.0, b.toppedUpBalance!!, 0.0)
        val request = env.server.takeRequest()
        assertEquals("/user/balance", request.path)
        assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
    }

    @Test
    fun `parses cny balance`() = runTest {
        val b = balance("""{"is_available":true,"balance_infos":[${info("CNY", "110.00", "10.00", "100.00")}]}""")
        assertEquals("CNY", b.currency)
        assertEquals(110.0, b.totalBalance, 0.0)
        assertEquals(100.0, b.toppedUpBalance!!, 0.0)
    }

    @Test
    fun `prefers usd when both currencies present`() = runTest {
        val b = balance(
            """{"is_available":true,"balance_infos":[${info("CNY", "100.00", "0.00", "100.00")},${info("USD", "20.00", "5.00", "15.00")}]}"""
        )
        assertEquals("USD", b.currency)
        assertEquals(20.0, b.totalBalance, 0.0)
    }

    @Test
    fun `prefers positive cny over empty usd`() = runTest {
        val b = balance(
            """{"is_available":true,"balance_infos":[${info("USD", "0.00", "0.00", "0.00")},${info("CNY", "100.00", "0.00", "100.00")}]}"""
        )
        assertEquals("CNY", b.currency)
        assertEquals(100.0, b.totalBalance, 0.0)
    }

    @Test
    fun `zero balance unavailable account`() = runTest {
        val b = balance("""{"is_available":false,"balance_infos":[${info("USD", "0.00", "0.00", "0.00")}]}""")
        assertFalse(b.isAvailable)
        assertEquals(0.0, b.totalBalance, 0.0)
    }

    @Test
    fun `malformed balance string is a parse error`() = runTest {
        val result = fetch("""{"is_available":true,"balance_infos":[${info("USD", "not-a-number", "0.00", "0.00")}]}""")
        assertTrue(result is Result.Failure)
        val error = (result as Result.Failure).error
        assertTrue(error is AppError.ParseError)
    }

    @Test
    fun `empty balance infos is an unavailable snapshot`() = runTest {
        val b = balance("""{"is_available":true,"balance_infos":[]}""")
        assertFalse(b.isAvailable)
        assertEquals(0.0, b.totalBalance, 0.0)
    }

    @Test
    fun `array root is a parse error`() = runTest {
        val result = fetch("""[{ "is_available": true }]""")
        assertTrue(result is Result.Failure || (result as Result.Success).value.accountBalances.single().error is AppError.ParseError)
    }

    @Test
    fun `401 is a terminal auth error`() = runTest {
        env.enqueue(401, """{"error":{"message":"Authentication Fails"}}""")
        val result = repository.fetchQuota()
        assertTrue(result is Result.Failure && (result.error as? AppError.AuthError)?.isTerminal == true)
    }
}
