package fr.gildasledrogoff.tokenusage.core.network.zai
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.parse
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.parseFixture
import fr.gildasledrogoff.tokenusage.core.network.zai.ZaiDto.isMonthlyTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class ZaiDtoParsingTest {
    @Test
    fun `nominal quota response`() {
        val quota = parseFixture<ZaiDto.QuotaResponse>("zai/quota_nominal.json")
        assertTrue(quota.success)
        assertEquals(200, quota.code)
        val data = quota.data!!
        assertEquals("pro", data.level)
        assertEquals(2, data.limits.size)
        val tokens = data.limits[0]
        assertEquals(ZaiDto.TYPE_TOKENS_LIMIT, tokens.type)
        assertEquals(5, tokens.number)
        assertEquals(40, tokens.percentage)
        assertEquals(1795000000000L, tokens.nextResetTime)
        assertEquals(600, tokens.remaining)
        assertTrue(tokens.usageDetails.isEmpty())
        assertFalse(tokens.isMonthlyTools)
        val tools = data.limits[1]
        assertTrue(tools.isMonthlyTools)
        assertEquals(listOf("search-prime", "web-reader"), tools.usageDetails.map { it.modelCode })
        assertEquals(100, tools.usageDetails.sumOf { it.usage })
    }
    @Test
    fun `minimal response has no data`() {
        val quota = parseFixture<ZaiDto.QuotaResponse>("zai/quota_minimal.json")
        assertTrue(quota.success)
        assertNull(quota.data)
        assertEquals("", quota.msg)
    }
    @Test
    fun `unknown fields are ignored`() {
        val quota = parseFixture<ZaiDto.QuotaResponse>("zai/quota_unknown_fields.json")
        assertEquals("max", quota.data!!.level)
        assertEquals(12, quota.data!!.limits.single().percentage)
    }
    @Test
    fun `null level and null list fall back to defaults`() {
        val quota = parseFixture<ZaiDto.QuotaResponse>("zai/quota_nulls.json")
        assertEquals("", quota.msg)
        assertEquals("", quota.data!!.level)
        assertTrue(quota.data!!.limits.isEmpty())
    }
    @Test
    fun `invalid token error body`() {
        val quota = parseFixture<ZaiDto.QuotaResponse>("zai/error_invalid_token.json")
        assertFalse(quota.success)
        assertEquals(ZaiDto.ERROR_CODE_INVALID_TOKEN, quota.code)
        assertNull(quota.data)
    }
    @Test
    fun `numbers sent as strings are accepted`() {
        val limit = parse<ZaiDto.QuotaLimit>("""{"type": "TOKENS_LIMIT", "percentage": "75", "nextResetTime": "1795000000000"}""")
        assertEquals(75, limit.percentage)
        assertEquals(1795000000000L, limit.nextResetTime)
    }
    @Test
    fun `empty limits array`() {
        val quota = parse<ZaiDto.QuotaResponse>("""{"code": 200, "success": true, "data": {"limits": [], "level": "lite"}}""")
        assertTrue(quota.data!!.limits.isEmpty())
        assertEquals("Lite", ZaiDto.planName(quota.data!!.level))
    }
}
