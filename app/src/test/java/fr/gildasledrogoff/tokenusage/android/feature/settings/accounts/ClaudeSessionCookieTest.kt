package fr.gildasledrogoff.tokenusage.feature.settings.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeSessionCookieTest {

    @Test
    fun `extracts current sid02 session key from cookie header`() {
        val header = "sessionKey=sk-ant-sid02-eWJhZWNkMTIz; lastActiveOrg=uuid-1234; intertid=machine-id"
        assertEquals(
            "sk-ant-sid02-eWJhZWNkMTIz",
            ClaudeSessionCookie.extract(header)
        )
    }

    @Test
    fun `extracts legacy sid01 session key`() {
        val header = "sessionKey=sk-ant-sid01-abcDEF123-_~.; other=x"
        assertEquals(
            "sk-ant-sid01-abcDEF123-_~.",
            ClaudeSessionCookie.extract(header)
        )
    }

    @Test
    fun `handles spacing around separators`() {
        val header = "  other=1; sessionKey=sk-ant-sid02-value ; last=2"
        assertEquals("sk-ant-sid02-value", ClaudeSessionCookie.extract(header))
    }

    @Test
    fun `returns null when header is null or blank`() {
        assertNull(ClaudeSessionCookie.extract(null))
        assertNull(ClaudeSessionCookie.extract(""))
        assertNull(ClaudeSessionCookie.extract("   "))
    }

    @Test
    fun `returns null when signed out or mid-login`() {
        assertNull(ClaudeSessionCookie.extract("intertid=machine-id; testingMode=0"))
    }

    @Test
    fun `returns null for malformed session key`() {
        assertNull(ClaudeSessionCookie.extract("sessionKey=not-a-session-key"))
        assertNull(ClaudeSessionCookie.extract("sessionKey=sk-ant-api03-should-not-match"))
        assertNull(ClaudeSessionCookie.extract("sessionKey="))
    }
}
