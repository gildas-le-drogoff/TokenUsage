package fr.gildasledrogoff.tokenusage.core.network.claude
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.parse
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.parseFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
class ClaudeDtoParsingTest {
    @Test
    fun `nominal usage response is fully decoded`() {
        val usage = parseFixture<ClaudeDto.OAuthUsageResponse>("claude/oauth_usage_nominal.json")
        assertEquals(42.5, usage.fiveHour?.utilization!!, 0.0)
        assertEquals("2026-11-20T18:00:00.000000+00:00", usage.fiveHour?.resetsAt)
        assertEquals(63.0, usage.sevenDay?.utilization!!, 0.0)
        assertNull(usage.sevenDayOauthApps)
        assertEquals(12.0, usage.sevenDayOpus?.utilization!!, 0.0)
        assertEquals(30.25, usage.sevenDaySonnet?.utilization!!, 0.0)
        val extra = usage.extraUsage!!
        assertTrue(extra.isEnabled)
        assertEquals(50.0, extra.monthlyLimit, 0.0)
        assertEquals(12.5, extra.usedCredits, 0.0)
        assertEquals("EUR", extra.currency)
        val limit = usage.limits.single()
        assertEquals("session", limit.kind)
        assertTrue(limit.isActive)
        assertEquals("Sonnet", limit.scope?.model?.displayName)
        assertEquals("Claude Code", limit.scope?.surface?.displayName)
        val breakdown = usage.sevenDayBreakdown!!
        assertEquals(2, breakdown.rows.size)
        assertEquals("code", breakdown.rows[0].key)
        assertEquals(23.0, breakdown.rows[1].percent, 0.0)
    }
    @Test
    fun `empty object yields defaults`() {
        val usage = parseFixture<ClaudeDto.OAuthUsageResponse>("claude/oauth_usage_minimal.json")
        assertNull(usage.fiveHour)
        assertNull(usage.sevenDay)
        assertNull(usage.extraUsage)
        assertTrue(usage.limits.isEmpty())
        assertNull(usage.sevenDayBreakdown)
    }
    @Test
    fun `unknown fields at every level are ignored`() {
        val usage = parseFixture<ClaudeDto.OAuthUsageResponse>("claude/oauth_usage_unknown_fields.json")
        assertEquals(10.0, usage.fiveHour?.utilization!!, 0.0)
        val limit = usage.limits.single()
        assertEquals("weekly", limit.kind)
        assertEquals(5.5, limit.percent, 0.0)
        assertNull(limit.scope?.model)
    }
    @Test
    fun `explicit nulls fall back to defaults`() {
        val usage = parseFixture<ClaudeDto.OAuthUsageResponse>("claude/oauth_usage_nulls.json")
        assertNull(usage.fiveHour?.utilization)
        assertNull(usage.fiveHour?.resetsAt)
        assertEquals("rate_limited", usage.fiveHour?.lockedReason)
        assertNull(usage.sevenDay)
        assertNull(usage.extraUsage)
        assertTrue(usage.limits.isEmpty())
        assertTrue(usage.sevenDayBreakdown!!.rows.isEmpty())
    }
    @Test
    fun `numbers sent as strings are accepted by the lenient parser`() {
        val usage = parse<ClaudeDto.OAuthUsageResponse>(
            """{"five_hour": {"utilization": "37.5"}, "limits": [{"percent": "12"}]}"""
        )
        assertEquals(37.5, usage.fiveHour?.utilization!!, 0.0)
        assertEquals(12.0, usage.limits.single().percent, 0.0)
    }
    @Test
    fun `integer utilization is read as double`() {
        val usage = parse<ClaudeDto.OAuthUsageResponse>("""{"seven_day": {"utilization": 100}}""")
        assertEquals(100.0, usage.sevenDay?.utilization!!, 0.0)
    }
    @Test
    fun `extra usage defaults currency to USD when absent or null`() {
        val absent = parse<ClaudeDto.OAuthExtraUsage>("""{"is_enabled": true}""")
        val nullCurrency = parse<ClaudeDto.OAuthExtraUsage>("""{"currency": null}""")
        assertEquals("USD", absent.currency)
        assertEquals("USD", nullCurrency.currency)
        assertFalse(nullCurrency.isEnabled)
    }
    @Test
    fun `error body decodes to an empty usage response`() {
        val usage = parseFixture<ClaudeDto.OAuthUsageResponse>("claude/error_unauthorized.json")
        assertNull(usage.fiveHour)
        assertTrue(usage.limits.isEmpty())
    }
    @Test
    fun `malformed json throws`() {
        try {
            parse<ClaudeDto.OAuthUsageResponse>("""{"five_hour": """)
            fail("JSON tronqué : une exception était attendue")
        } catch (_: Exception) {
        }
    }
    @Test
    fun `token refresh response nominal`() {
        val token = parseFixture<ClaudeDto.TokenRefreshResponse>("claude/token_refresh_nominal.json")
        assertEquals("sk-ant-oat01-EXAMPLE-ACCESS-TOKEN", token.accessToken)
        assertEquals("sk-ant-ort01-EXAMPLE-REFRESH-TOKEN", token.refreshToken)
        assertEquals(28800, token.expiresIn)
        assertEquals("Bearer", token.tokenType)
    }
    @Test
    fun `token refresh response without refresh token keeps it null`() {
        val token = parse<ClaudeDto.TokenRefreshResponse>("""{"access_token": "a", "expires_in": 60}""")
        assertNull(token.refreshToken)
        assertNull(token.tokenType)
    }
    @Test
    fun `token refresh response without access token is rejected`() {
        try {
            parse<ClaudeDto.TokenRefreshResponse>("""{"expires_in": 60}""")
            fail("access_token est obligatoire")
        } catch (_: Exception) {
        }
    }
    @Test
    fun `web organizations support uuid and legacy id`() {
        val orgs = parseFixture<List<ClaudeDto.WebOrganization>>("claude/web_organizations.json")
        assertEquals(2, orgs.size)
        assertEquals("00000000-0000-4000-8000-000000000001", orgs[0].orgId)
        assertTrue(orgs[0].hasCapability("claude_pro"))
        assertFalse(orgs[0].hasCapability("api"))
        assertEquals("legacy-org-id", orgs[1].orgId)
        assertNull(orgs[1].name)
        assertTrue(orgs[1].capabilities.isEmpty())
        assertNotNull(orgs[1])
    }
    @Test
    fun `web organization with blank uuid falls back to id`() {
        val org = parse<ClaudeDto.WebOrganization>("""{"uuid": "  ", "id": "fallback"}""")
        assertEquals("fallback", org.orgId)
    }
    @Test
    fun `web organization without any id yields an empty orgId`() {
        assertEquals("", parse<ClaudeDto.WebOrganization>("{}").orgId)
    }
    @Test
    fun `empty organization list`() {
        assertTrue(parse<List<ClaudeDto.WebOrganization>>("[]").isEmpty())
    }
}
