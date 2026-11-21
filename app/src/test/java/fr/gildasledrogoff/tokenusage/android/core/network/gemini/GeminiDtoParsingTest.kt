package fr.gildasledrogoff.tokenusage.core.network.gemini
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.json
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.parse
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.parseFixture
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
class GeminiDtoParsingTest {
    @Test
    fun `load code assist nominal with project as string`() {
        val response = parseFixture<GeminiDto.LoadCodeAssistResponse>("gemini/load_code_assist_nominal.json")
        assertEquals("standard-tier", response.currentTier?.id)
        assertEquals("g1-pro-tier", response.paidTier?.id)
        assertEquals("example-project-123", (response.cloudaicompanionProject as JsonPrimitive).content)
        assertTrue(response.ineligibleTiers.isEmpty())
    }
    @Test
    fun `load code assist accepts project as object and lists ineligible tiers`() {
        val response = parseFixture<GeminiDto.LoadCodeAssistResponse>("gemini/load_code_assist_project_object.json")
        assertTrue(response.cloudaicompanionProject is JsonObject)
        assertNull(response.paidTier)
        assertEquals("UNSUPPORTED_LOCATION", response.ineligibleTiers.single().reasonCode)
    }
    @Test
    fun `load code assist empty object`() {
        val response = parse<GeminiDto.LoadCodeAssistResponse>("{}")
        assertNull(response.currentTier)
        assertNull(response.cloudaicompanionProject)
    }
    @Test
    fun `quota nominal with null reset and integer fraction`() {
        val quota = parseFixture<GeminiDto.QuotaResponse>("gemini/quota_nominal.json")
        assertEquals(3, quota.buckets.size)
        assertEquals("gemini-2.5-pro", quota.buckets[0].modelId)
        assertEquals(0.75, quota.buckets[0].remainingFraction!!, 0.0)
        assertEquals(0.0, quota.buckets[2].remainingFraction!!, 0.0)
        assertNull(quota.buckets[2].resetTime)
        assertNull(quota.buckets[2].tokenType)
    }
    @Test
    fun `quota empty buckets and unknown fields`() {
        assertTrue(parseFixture<GeminiDto.QuotaResponse>("gemini/quota_empty.json").buckets.isEmpty())
        assertTrue(parse<GeminiDto.QuotaResponse>("{}").buckets.isEmpty())
        assertTrue(parse<GeminiDto.QuotaResponse>("""{"buckets": null}""").buckets.isEmpty())
        val unknown = parseFixture<GeminiDto.QuotaResponse>("gemini/quota_unknown_fields.json")
        assertEquals(0.5, unknown.buckets.single().remainingFraction!!, 0.0)
    }
    @Test
    fun `remaining fraction as string is accepted`() {
        val bucket = parse<GeminiDto.QuotaBucket>("""{"remainingFraction": "0.25"}""")
        assertEquals(0.25, bucket.remainingFraction!!, 0.0)
    }
    @Test
    fun `error body decodes to empty quota`() {
        val quota = parseFixture<GeminiDto.QuotaResponse>("gemini/error_permission_denied.json")
        assertTrue(quota.buckets.isEmpty())
    }
    @Test
    fun `token refresh nominal`() {
        val token = parseFixture<GeminiDto.TokenRefreshResponse>("gemini/token_refresh_nominal.json")
        assertEquals("ya29.EXAMPLE", token.accessToken)
        assertNull(token.refreshToken)
        assertEquals(3599, token.expiresIn)
        assertEquals("EXAMPLE.PAYLOAD.SIG", token.idToken)
    }
    @Test
    fun `projects response tolerates missing ids and null labels`() {
        val projects = parseFixture<GeminiDto.ProjectsResponse>("gemini/projects_nominal.json").projects
        assertEquals(3, projects.size)
        assertEquals("enabled", projects[0].labels["generative-language"])
        assertTrue(projects[1].labels.isEmpty())
        assertNull(projects[2].projectId)
    }
    @Test
    fun `retrieve user quota request omits nothing and serializes project`() {
        val encoded = json.encodeToString(GeminiDto.RetrieveUserQuotaRequest.serializer(), GeminiDto.RetrieveUserQuotaRequest("p"))
        assertEquals("""{"project":"p"}""", encoded)
    }
    @Test
    fun `load code assist request always carries its metadata`() {
        val encoded = json.encodeToString(GeminiDto.LoadCodeAssistRequest.serializer(), GeminiDto.LoadCodeAssistRequest())
        assertTrue(encoded.contains("\"ideType\":\"GEMINI_CLI\""))
        assertTrue(encoded.contains("\"pluginType\":\"GEMINI\""))
    }
    private fun idToken(payloadJson: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "header." + encoder.encodeToString(payloadJson.toByteArray()) + ".sig"
    }
    @Test
    fun `hostedDomainOf reads the hd claim`() {
        assertEquals("example.invalid", GeminiDto.hostedDomainOf(idToken("""{"hd": " example.invalid "}""")))
    }
    @Test
    fun `hostedDomainOf returns null when claim missing, blank or token malformed`() {
        assertNull(GeminiDto.hostedDomainOf(null))
        assertNull(GeminiDto.hostedDomainOf(""))
        assertNull(GeminiDto.hostedDomainOf("only-one-part"))
        assertNull(GeminiDto.hostedDomainOf("a..c"))
        assertNull(GeminiDto.hostedDomainOf(idToken("""{"sub": "x"}""")))
        assertNull(GeminiDto.hostedDomainOf(idToken("""{"hd": "   "}""")))
        assertNull(GeminiDto.hostedDomainOf("a.!!!not-base64!!!.c"))
        assertNull(GeminiDto.hostedDomainOf(idToken("not json")))
    }
    @Test
    fun `refreshedHostedDomain keeps previous value when no id token is returned`() {
        assertEquals("kept.invalid", GeminiDto.refreshedHostedDomain(null, "kept.invalid"))
        assertEquals("kept.invalid", GeminiDto.refreshedHostedDomain("  ", "kept.invalid"))
    }
    @Test
    fun `refreshedHostedDomain clears the domain when the new token has none`() {
        assertEquals("", GeminiDto.refreshedHostedDomain(idToken("""{"sub": "x"}"""), "old.invalid"))
        assertEquals("new.invalid", GeminiDto.refreshedHostedDomain(idToken("""{"hd": "new.invalid"}"""), "old.invalid"))
    }
    @Test
    fun `consumer tier deprecation signals`() {
        assertTrue(GeminiDto.isConsumerTierDeprecationSignal("Error: UNSUPPORTED_CLIENT"))
        assertTrue(GeminiDto.isConsumerTierDeprecationSignal("IneligibleTierError: foo"))
        assertTrue(GeminiDto.isConsumerTierDeprecationSignal("Gemini Code Assist is no longer supported for you"))
        assertTrue(GeminiDto.isConsumerTierDeprecationSignal("Please migrate to Antigravity for Gemini"))
        assertFalse(GeminiDto.isConsumerTierDeprecationSignal("Permission denied"))
        assertFalse(GeminiDto.isConsumerTierDeprecationSignal("no longer supported"))
        assertFalse(GeminiDto.isConsumerTierDeprecationSignal(""))
    }
}
