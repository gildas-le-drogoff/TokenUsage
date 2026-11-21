package fr.gildasledrogoff.tokenusage.core.network.codex

import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.parse
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.parseFixture
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexDtoParsingTest {

    @Test
    fun `nominal usage response`() {
        val usage = parseFixture<CodexDto.UsageResponse>("codex/usage_nominal.json")

        assertEquals("plus", usage.planType)
        val primary = usage.rateLimit?.primaryWindow!!
        assertEquals(35.0, primary.usedPercent, 0.0)
        assertEquals(1795000000L, primary.resetAt)
        assertEquals(18000L, primary.limitWindowSeconds)
        assertEquals(604800L, usage.rateLimit?.secondaryWindow?.limitWindowSeconds)
        assertTrue(usage.credits!!.hasCredits)
        assertFalse(usage.credits!!.unlimited)
        assertEquals("12.34", (usage.credits!!.balance as JsonPrimitive).content)
    }

    @Test
    fun `empty object yields defaults`() {
        val usage = parseFixture<CodexDto.UsageResponse>("codex/usage_minimal.json")

        assertNull(usage.planType)
        assertNull(usage.rateLimit)
        assertNull(usage.credits)
    }

    @Test
    fun `unknown fields and nulls are tolerated`() {
        val usage = parseFixture<CodexDto.UsageResponse>("codex/usage_unknown_fields.json")

        assertEquals("pro", usage.planType)
        val primary = usage.rateLimit?.primaryWindow!!
        assertEquals(1.0, primary.usedPercent, 0.0)
        assertNull(primary.resetAt)
        assertNull(usage.rateLimit?.secondaryWindow)
        assertTrue(usage.credits!!.unlimited)
        assertTrue(usage.credits!!.balance is JsonNull || usage.credits!!.balance == null)
    }

    @Test
    fun `credit balance may be a number or a string`() {
        val numeric = parseFixture<CodexDto.UsageResponse>("codex/usage_credits_variants.json")
        val textual = parse<CodexDto.UsageResponse>("""{"credits": {"balance": "3.5"}}""")

        assertEquals(7.5, (numeric.credits!!.balance as JsonPrimitive).content.toDouble(), 0.0)
        assertEquals(3.5, (textual.credits!!.balance as JsonPrimitive).content.toDouble(), 0.0)
    }

    @Test
    fun `used percent as string is accepted and null falls back to zero`() {
        val window = parse<CodexDto.RateLimitWindow>("""{"used_percent": "55.5"}""")
        val nullPercent = parse<CodexDto.RateLimitWindow>("""{"used_percent": null}""")

        assertEquals(55.5, window.usedPercent, 0.0)
        assertEquals(0.0, nullPercent.usedPercent, 0.0)
    }

    @Test
    fun `empty window object has default zero usage`() {
        val usage = parse<CodexDto.UsageResponse>("""{"rate_limit": {"primary_window": {}}}""")

        assertEquals(0.0, usage.rateLimit?.primaryWindow?.usedPercent!!, 0.0)
    }

    @Test
    fun `token error body exposes a terminal error code`() {
        val error = parseFixture<CodexDto.TokenErrorResponse>("codex/error_expired_token.json")

        assertEquals("refresh_token_expired", error.error)
        assertTrue(error.error in CodexDto.TERMINAL_ERROR_CODES)
        assertTrue(error.errorDescription!!.isNotBlank())
    }

    @Test
    fun `terminal error codes cover single-use refresh token failures`() {
        assertTrue("refresh_token_reused" in CodexDto.TERMINAL_ERROR_CODES)
        assertTrue("invalid_grant" in CodexDto.TERMINAL_ERROR_CODES)
        assertFalse("temporarily_unavailable" in CodexDto.TERMINAL_ERROR_CODES)
    }

    @Test
    fun `token refresh response ignores id_token`() {
        val token = parseFixture<CodexDto.TokenRefreshResponse>("codex/token_refresh_nominal.json")

        assertEquals("EXAMPLE-ACCESS-TOKEN", token.accessToken)
        assertEquals("EXAMPLE-REFRESH-TOKEN", token.refreshToken)
        assertEquals(3600, token.expiresIn)
    }

    @Test
    fun `token refresh response keeps optional fields null`() {
        val token = parse<CodexDto.TokenRefreshResponse>("""{"access_token": "a"}""")

        assertNull(token.refreshToken)
        assertNull(token.expiresIn)
    }

    @Test
    fun `token refresh request encodes defaults`() {
        val encoded = fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.json
            .encodeToString(CodexDto.TokenRefreshRequest.serializer(), CodexDto.TokenRefreshRequest(refreshToken = "r"))

        assertTrue(encoded.contains("\"client_id\":\"${CodexDto.CODEX_CLIENT_ID}\""))
        assertTrue(encoded.contains("\"grant_type\":\"refresh_token\""))
        assertTrue(encoded.contains("\"refresh_token\":\"r\""))
        assertTrue(encoded.contains("\"scope\":\"openid profile email\""))
    }
}
