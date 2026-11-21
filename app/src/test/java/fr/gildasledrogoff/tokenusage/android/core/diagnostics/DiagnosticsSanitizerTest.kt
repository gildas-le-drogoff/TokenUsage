package fr.gildasledrogoff.tokenusage.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsSanitizerTest {

    private fun assertNoLeak(secret: String, input: String) {
        val out = DiagnosticsSanitizer.sanitize(input)
        assertFalse("« $secret » a fuité dans : $out", out.contains(secret))
    }

    @Test
    fun `plain messages are kept`() {
        val message = "HTTP 503 from upstream, retrying later"
        assertEquals(message, DiagnosticsSanitizer.sanitize(message))
    }

    @Test
    fun `null and empty become empty`() {
        assertEquals("", DiagnosticsSanitizer.sanitize(null))
        assertEquals("", DiagnosticsSanitizer.sanitize(""))
    }

    @Test
    fun `authorization headers are fully redacted`() {
        assertNoLeak("abc123SECRET", "Authorization: Bearer abc123SECRET")
        assertNoLeak("abc123SECRET", "request failed\nauthorization=Basic abc123SECRET\nnext line")
        assertNoLeak("abc123SECRET", "Proxy-Authorization: abc123SECRET")
        assertTrue(DiagnosticsSanitizer.sanitize("Authorization: Bearer abc123SECRET").contains("Authorization"))
        assertTrue(DiagnosticsSanitizer.sanitize("a\nAuthorization: x1\nb").contains("\nb"))
    }

    @Test
    fun `cookies and session keys are redacted`() {
        assertNoLeak("sk-ant-sid01-VERYSECRET", "Cookie: sessionKey=sk-ant-sid01-VERYSECRET; other=1")
        assertNoLeak("VERYSECRET", "Set-Cookie: sessionKey=VERYSECRET; Path=/; HttpOnly")
        assertNoLeak("VERYSECRET", "sessionKey=VERYSECRET")
        assertNoLeak("VERYSECRET", """{"session_key":"VERYSECRET"}""")
    }

    @Test
    fun `bearer tokens in free text are redacted`() {
        assertNoLeak("tok_1234567890", "failed with Bearer tok_1234567890 on retry")
    }

    @Test
    fun `named secrets in json query strings and key value pairs are redacted`() {
        assertNoLeak("S3CR3T-value", """{"access_token":"S3CR3T-value","expires_in":3600}""")
        assertNoLeak("S3CR3T-value", """{"refresh_token": "S3CR3T-value"}""")
        assertNoLeak("S3CR3T-value", "GET /v1/costs?api_key=S3CR3T-value&limit=1")
        assertNoLeak("S3CR3T-value", "https://example.invalid/x?key=S3CR3T-value")
        assertNoLeak("S3CR3T-value", "client_secret=S3CR3T-value")
        assertNoLeak("S3CR3T-value", "password: S3CR3T-value")
        assertNoLeak("S3CR3T-value", "token = S3CR3T-value")
    }

    @Test
    fun `well known token shapes are redacted even without a label`() {
        listOf(
            "sk-ant-oat01-AbCdEfGh1234567890",
            "sk-proj-ABCDEFGH12345678",
            "ghp_ABCDEFGHIJKLMNOP1234",
            "gho_ABCDEFGHIJKLMNOP1234",
            "github_pat_11ABCDEFG0abcdefgh",
            "ya29.a0AfH6SMBexampleexample-_",
            "AIzaSyA-1234567890abcdefghijklmnopqrstu",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl"
        ).forEach { secret -> assertNoLeak(secret, "upstream said: $secret was rejected") }
    }

    @Test
    fun `long opaque strings are redacted`() {
        val opaque = "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8"
        assertNoLeak(opaque, "unexpected value $opaque in response")
    }

    @Test
    fun `short identifiers and class names survive`() {
        val text = "kotlinx.serialization.SerializationException at line 12, code 401"
        assertEquals(text, DiagnosticsSanitizer.sanitize(text))
    }

    @Test
    fun `email addresses are masked`() {
        val out = DiagnosticsSanitizer.sanitize("account jane.doe+test@example.invalid is locked")
        assertFalse(out.contains("jane.doe"))
        assertTrue(out.contains("[email]"))
    }

    @Test
    fun `output is truncated`() {
        val out = DiagnosticsSanitizer.sanitize("x ".repeat(1_000))
        assertEquals(DiagnosticsSanitizer.MAX_LENGTH + 1, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun `sanitizing is idempotent`() {
        val once = DiagnosticsSanitizer.sanitize("Authorization: Bearer abc123SECRET and sessionKey=zzz and a@b.example")
        assertEquals(once, DiagnosticsSanitizer.sanitize(once))
    }

    @Test
    fun `multiple secrets in a single message are all removed`() {
        val input = "refresh_token=AAAA1111 access_token=BBBB2222 Authorization: Bearer CCCC3333 sk-ant-DDDD44445555"
        val out = DiagnosticsSanitizer.sanitize(input)
        listOf("AAAA1111", "BBBB2222", "CCCC3333", "DDDD44445555").forEach { assertFalse(out, out.contains(it)) }
    }
}
