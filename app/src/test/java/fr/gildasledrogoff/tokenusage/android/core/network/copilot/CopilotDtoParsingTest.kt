package fr.gildasledrogoff.tokenusage.core.network.copilot
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.parse
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.parseFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class CopilotDtoParsingTest {
    @Test
    fun `nominal user response exposes premium snapshot`() {
        val user = parseFixture<CopilotDto.UserResponse>("copilot/user_nominal.json")
        assertEquals("individual_pro", user.copilotPlan)
        assertEquals("2026-12-01T00:00:00.000Z", user.quotaResetDateUtc)
        assertEquals(setOf("premium_interactions", "chat", "completions"), user.snapshots.keys)
        val premium = user.snapshots.getValue(CopilotDto.KEY_PREMIUM)
        assertEquals(300.0, premium.entitlement!!, 0.0)
        assertEquals(40.0, premium.percentRemaining!!, 0.0)
        assertFalse(premium.isPlaceholder)
        assertEquals(0.6, premium.utilization!!, 1e-9)
    }
    @Test
    fun `unlimited snapshots have no utilization and are not placeholders`() {
        val user = parseFixture<CopilotDto.UserResponse>("copilot/user_nominal.json")
        val chat = user.snapshots.getValue(CopilotDto.KEY_CHAT)
        val completions = user.snapshots.getValue(CopilotDto.KEY_COMPLETIONS)
        assertTrue(chat.unlimited)
        assertFalse(chat.isPlaceholder)
        assertNull(chat.utilization)
        assertTrue(completions.unlimited)
        assertNull(completions.utilization)
    }
    @Test
    fun `free plan derives snapshots from monthly quotas`() {
        val user = parseFixture<CopilotDto.UserResponse>("copilot/user_free_plan.json")
        val chat = user.monthlySnapshot(CopilotDto.KEY_CHAT)!!
        assertEquals(50.0, chat.entitlement!!, 0.0)
        assertEquals(35.0, chat.remaining!!, 0.0)
        assertEquals(70.0, chat.percentRemaining!!, 1e-9)
        assertEquals(0.3, chat.utilization!!, 1e-9)
        assertNotNull(user.monthlySnapshot(CopilotDto.KEY_COMPLETIONS))
        assertNull(user.monthlySnapshot(CopilotDto.KEY_PREMIUM))
        assertTrue(user.snapshots.isEmpty())
    }
    @Test
    fun `monthly snapshot clamps remaining above entitlement`() {
        val user = parse<CopilotDto.UserResponse>(
            """{"monthly_quotas": {"chat": 10}, "limited_user_quotas": {"chat": 25}}"""
        )
        assertEquals(100.0, user.monthlySnapshot("chat")!!.percentRemaining!!, 0.0)
    }
    @Test
    fun `monthly snapshot is null for zero entitlement or missing remaining`() {
        val zero = parse<CopilotDto.UserResponse>("""{"monthly_quotas": {"chat": 0}, "limited_user_quotas": {"chat": 0}}""")
        val noRemaining = parse<CopilotDto.UserResponse>("""{"monthly_quotas": {"chat": 10}}""")
        assertNull(zero.monthlySnapshot("chat"))
        assertNull(noRemaining.monthlySnapshot("chat"))
    }
    @Test
    fun `string numbers and malformed snapshots are tolerated`() {
        val user = parseFixture<CopilotDto.UserResponse>("copilot/user_unknown_fields.json")
        assertEquals(setOf("premium_interactions"), user.snapshots.keys)
        val premium = user.snapshots.getValue("premium_interactions")
        assertEquals(300.0, premium.entitlement!!, 0.0)
        assertEquals(30.0, premium.remaining!!, 0.0)
        assertEquals(0.9, premium.utilization!!, 1e-9)
    }
    @Test
    fun `minimal user response has empty defaults`() {
        val user = parseFixture<CopilotDto.UserResponse>("copilot/user_minimal.json")
        assertEquals("", user.login)
        assertEquals("", user.copilotPlan)
        assertFalse(user.tokenBasedBilling)
        assertTrue(user.snapshots.isEmpty())
        assertNull(user.monthlySnapshot("chat"))
    }
    @Test
    fun `null snapshots object`() {
        val user = parse<CopilotDto.UserResponse>("""{"quota_snapshots": null}""")
        assertTrue(user.snapshots.isEmpty())
    }
    @Test
    fun `placeholder detection`() {
        assertTrue(CopilotDto.QuotaSnapshot().isPlaceholder)
        assertTrue(CopilotDto.QuotaSnapshot(entitlement = 0.0, remaining = 0.0, percentRemaining = 100.0).isPlaceholder)
        assertFalse(CopilotDto.QuotaSnapshot(entitlement = 10.0, remaining = 5.0).isPlaceholder)
        assertFalse(CopilotDto.QuotaSnapshot(unlimited = true).isPlaceholder)
    }
    @Test
    fun `utilization falls back to entitlement and remaining and never goes negative`() {
        assertEquals(0.25, CopilotDto.QuotaSnapshot(entitlement = 100.0, remaining = 75.0).utilization!!, 1e-9)
        assertEquals(0.0, CopilotDto.QuotaSnapshot(percentRemaining = 120.0).utilization!!, 0.0)
        assertNull(CopilotDto.QuotaSnapshot(entitlement = 100.0).utilization)
    }
    @Test
    fun `non finite or non numeric values are dropped`() {
        val snapshot = CopilotDto.QuotaSnapshot.fromJson(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"entitlement": "abc", "remaining": null, "percent_remaining": "NaN", "quota_id": 5}"""
            )
        )!!
        assertNull(snapshot.entitlement)
        assertNull(snapshot.remaining)
        assertNull(snapshot.percentRemaining)
        assertEquals("", snapshot.quotaId)
        assertNull(CopilotDto.QuotaSnapshot.fromJson(null))
    }
    @Test
    fun `device code response`() {
        val code = parseFixture<CopilotDto.DeviceCodeResponse>("copilot/device_code.json")
        assertEquals("ABCD-1234", code.userCode)
        assertEquals(899, code.expiresIn)
        assertEquals(5, code.interval)
    }
    @Test
    fun `device code response falls back to defaults`() {
        val code = parse<CopilotDto.DeviceCodeResponse>("{}")
        assertEquals(CopilotDto.VERIFICATION_URL, code.verificationUri)
        assertEquals(900, code.expiresIn)
        assertEquals(5, code.interval)
    }
    @Test
    fun `access token pending and success bodies`() {
        val pending = parseFixture<CopilotDto.AccessTokenResponse>("copilot/access_token_pending.json")
        val ok = parseFixture<CopilotDto.AccessTokenResponse>("copilot/access_token_ok.json")
        assertNull(pending.accessToken)
        assertEquals(CopilotDto.ERROR_PENDING, pending.error)
        assertEquals("gho_EXAMPLE", ok.accessToken)
        assertNull(ok.error)
    }
}
