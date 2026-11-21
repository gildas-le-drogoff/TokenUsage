package fr.gildasledrogoff.tokenusage.core.provider
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class CredentialFormTest {
    private val apiKey = apiKeyForm(labelRes = 1, fieldHintRes = 2) { Credential.ApiKeyCredential(it) }
    @Test
    fun `api key form builds a credential from the trimmed key`() {
        assertEquals(
            Credential.ApiKeyCredential("sk-1"),
            apiKey.assemble(mapOf(KEY_API_KEY to "  sk-1 \n"), null)
        )
    }
    @Test
    fun `api key form needs a key`() {
        assertNull(apiKey.assemble(emptyMap(), null))
        assertNull(apiKey.assemble(mapOf(KEY_API_KEY to "   "), Credential.ApiKeyCredential("old")))
    }
    @Test
    fun `secret keys are the masked fields`() {
        val form = CredentialForm(
            fields = listOf(
                CredentialField("token", 1),
                CredentialField("accountId", 2, secret = false)
            ),
            assemble = { _, _ -> null }
        )
        assertEquals(setOf("token"), form.secretKeys)
        assertTrue(form.options.isEmpty())
    }
}
