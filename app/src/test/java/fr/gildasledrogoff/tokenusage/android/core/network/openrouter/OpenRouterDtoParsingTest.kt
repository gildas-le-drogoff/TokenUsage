package fr.gildasledrogoff.tokenusage.core.network.openrouter
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.parse
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.parseFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
class OpenRouterDtoParsingTest {
    @Test
    fun `nominal key response`() {
        val data = parseFixture<OpenRouterDto.KeyResponse>("openrouter/key_nominal.json").data!!
        assertEquals(20.0, data.limit!!, 0.0)
        assertEquals(OpenRouterDto.RESET_MONTHLY, data.limitReset)
        assertEquals(14.75, data.limitRemaining!!, 0.0)
        assertEquals(5.25, data.usage!!, 0.0)
        assertEquals(0.5, data.usageDaily!!, 0.0)
        assertEquals(2.0, data.usageWeekly!!, 0.0)
        assertFalse(data.isFreeTier)
        assertFalse(data.isManagementKey)
    }
    @Test
    fun `key without limit keeps nulls`() {
        val data = parseFixture<OpenRouterDto.KeyResponse>("openrouter/key_no_limit.json").data!!
        assertNull(data.limit)
        assertNull(data.limitRemaining)
        assertNull(data.limitReset)
        assertEquals(1.5, data.usage!!, 0.0)
    }
    @Test
    fun `empty object has no data`() {
        assertNull(parseFixture<OpenRouterDto.KeyResponse>("openrouter/key_empty.json").data)
        assertNull(parse<OpenRouterDto.CreditsResponse>("{}").data)
    }
    @Test
    fun `integer amounts are read as double`() {
        val data = parse<OpenRouterDto.KeyResponse>("""{"data": {"limit": 10, "usage": 3}}""").data!!
        assertEquals(10.0, data.limit!!, 0.0)
        assertEquals(3.0, data.usage!!, 0.0)
    }
    @Test
    fun `amounts as strings are accepted`() {
        val data = parse<OpenRouterDto.KeyResponse>("""{"data": {"limit_remaining": "4.5"}}""").data!!
        assertEquals(4.5, data.limitRemaining!!, 0.0)
    }
    @Test
    fun `null label falls back to empty string`() {
        assertEquals("", parse<OpenRouterDto.KeyResponse>("""{"data": {"label": null}}""").data!!.label)
    }
    @Test
    fun `credits nominal`() {
        val credits = parseFixture<OpenRouterDto.CreditsResponse>("openrouter/credits_nominal.json").data!!
        assertEquals(25.0, credits.totalCredits!!, 0.0)
        assertEquals(7.125, credits.totalUsage!!, 0.0)
    }
    @Test
    fun `error body decodes to no data`() {
        assertNull(parseFixture<OpenRouterDto.KeyResponse>("openrouter/error_unauthorized.json").data)
    }
}
