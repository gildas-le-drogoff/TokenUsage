package fr.gildasledrogoff.tokenusage.core.domain.model
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class CredentialTest {
    private fun isValid(token: String) = Credential.ClaudeWebCredential.isSessionKey(token)
    @Test
    fun `legacy sid01 key is valid`() {
        assertTrue(isValid("sk-ant-sid01-eWJhZWNkMTIz"))
    }
    @Test
    fun `current sid02 key is valid`() {
        assertTrue(isValid("sk-ant-sid02-eWJhZWNkMTIz"))
    }
    @Test
    fun `future sid03 generation is valid without code change`() {
        assertTrue(isValid("sk-ant-sid03-eWJhZWNkMTIz"))
    }
    @Test
    fun `oauth token is rejected`() {
        assertFalse(isValid("sk-ant-oat01-eWJhZWNkMTIz"))
    }
    @Test
    fun `api key is rejected`() {
        assertFalse(isValid("sk-ant-api03-eWJhZWNkMTIz"))
    }
    @Test
    fun `bare prefix without key body is rejected`() {
        assertFalse(isValid("sk-ant-sid01-"))
    }
    @Test
    fun `garbage and blank are rejected`() {
        assertFalse(isValid("not-a-session-key"))
        assertFalse(isValid(""))
    }
}
