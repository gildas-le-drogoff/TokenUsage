package fr.gildasledrogoff.tokenusage.core.network.deepseek
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.parse
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.parseFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class DeepseekDtoParsingTest {
    @Test
    fun `nominal balance with two currencies`() {
        val balance = parseFixture<DeepseekDto.BalanceResponse>("deepseek/balance_nominal.json")
        assertEquals(true, balance.isAvailable)
        assertEquals(2, balance.balanceInfos.size)
        val cny = balance.balanceInfos[0]
        assertEquals("CNY", cny.currency)
        assertEquals("110.00", cny.totalBalance)
        assertEquals("10.00", cny.grantedBalance)
        assertEquals("100.00", cny.toppedUpBalance)
        assertEquals("USD", balance.balanceInfos[1].currency)
    }
    @Test
    fun `empty balance list`() {
        val balance = parseFixture<DeepseekDto.BalanceResponse>("deepseek/balance_empty.json")
        assertEquals(false, balance.isAvailable)
        assertTrue(balance.balanceInfos.isEmpty())
    }
    @Test
    fun `empty object and null list`() {
        val empty = parse<DeepseekDto.BalanceResponse>("{}")
        val nullList = parse<DeepseekDto.BalanceResponse>("""{"balance_infos": null}""")
        assertNull(empty.isAvailable)
        assertTrue(empty.balanceInfos.isEmpty())
        assertTrue(nullList.balanceInfos.isEmpty())
    }
    @Test
    fun `unknown fields and null amounts`() {
        val balance = parseFixture<DeepseekDto.BalanceResponse>("deepseek/balance_unknown_fields.json")
        val info = balance.balanceInfos.single()
        assertEquals("1.50", info.totalBalance)
        assertNull(info.grantedBalance)
    }
    @Test
    fun `numeric amounts are coerced to strings by the lenient parser`() {
        val info = parse<DeepseekDto.BalanceInfo>("""{"currency": "USD", "total_balance": 12.5}""")
        assertEquals("12.5", info.totalBalance)
    }
    @Test
    fun `error body decodes to an empty balance`() {
        val balance = parseFixture<DeepseekDto.BalanceResponse>("deepseek/error_unauthorized.json")
        assertTrue(balance.balanceInfos.isEmpty())
        assertFalse(balance.isAvailable == true)
    }
}
