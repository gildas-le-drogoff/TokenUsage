package fr.gildasledrogoff.tokenusage.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    private val payload = """{"hello":"tokenusage","tokens":["sk-ant","ghp_x"],"n":42}""".toByteArray()

    @Test
    fun `password policy accepts any character with at least 6 characters`() {
        assertFalse(BackupCrypto.isValidPassword(""))
        assertFalse(BackupCrypto.isValidPassword("12345"))
        assertTrue(BackupCrypto.isValidPassword("123456"))
        assertTrue(BackupCrypto.isValidPassword("ab cd!"))
        assertTrue(BackupCrypto.isValidPassword("      "))
        assertTrue(BackupCrypto.isValidPassword("é à ü ç"))
    }

    @Test
    fun `emoji count as one character each`() {
        assertFalse(BackupCrypto.isValidPassword("😀😀😀😀😀"))
        assertTrue(BackupCrypto.isValidPassword("😀😀😀😀😀😀"))
        assertTrue(BackupCrypto.isValidPassword("👨‍👩‍👧a"))
    }

    @Test
    fun `import accepts legacy short pins`() {
        assertTrue(BackupCrypto.isUsableForImport("123"))
        assertFalse(BackupCrypto.isUsableForImport(""))
    }

    @Test
    fun `password with emojis spaces and accents roundtrips`() {
        val password = "  mot de passe 🔐 été 😀  "
        val encrypted = BackupCrypto.encrypt(payload, password)

        assertArrayEquals(payload, BackupCrypto.decrypt(encrypted, password))
    }

    @Test
    fun `exact password with edge spaces is never trimmed at creation`() {
        val encrypted = BackupCrypto.encrypt(payload, "  secret1  ")

        assertThrows(BackupCryptoException::class.java) {
            BackupCrypto.decrypt(encrypted, "secret2")
        }
        assertArrayEquals(payload, BackupCrypto.decrypt(encrypted, "  secret1  "))
    }

    @Test
    fun `import falls back to trimmed password when stray spaces were typed`() {
        val encrypted = BackupCrypto.encrypt(payload, "secret1")

        assertArrayEquals(payload, BackupCrypto.decrypt(encrypted, " secret1"))
        assertArrayEquals(payload, BackupCrypto.decrypt(encrypted, "secret1 \n"))
    }

    @Test
    fun `blank import password never decrypts`() {
        val encrypted = BackupCrypto.encrypt(payload, "      ")

        assertArrayEquals(payload, BackupCrypto.decrypt(encrypted, "      "))
        assertThrows(BackupCryptoException::class.java) {
            BackupCrypto.decrypt(BackupCrypto.encrypt(payload, "secret1"), "      ")
        }
    }

    @Test
    fun `unicode normalization makes composed and decomposed forms equivalent`() {
        val composed = "café secret"
        val decomposed = "café secret"
        val encrypted = BackupCrypto.encrypt(payload, composed)

        assertArrayEquals(payload, BackupCrypto.decrypt(encrypted, decomposed))
    }

    @Test
    fun `roundtrip encrypt then decrypt preserves payload`() {
        val encrypted = BackupCrypto.encrypt(payload, "123")

        assertNotEquals(payload, encrypted)
        assertArrayEquals(payload, BackupCrypto.decrypt(encrypted, "123"))
    }

    @Test
    fun `deflate inflate roundtrip and shrinks json`() {
        val configJson = (
            """{"hello":"tokenusage","tokens":["sk-ant","ghp_x"],"n":42},"""
        ).repeat(20).dropLast(1).let { "[$it]" }.toByteArray()

        val deflated = BackupCrypto.deflate(configJson)

        assertArrayEquals(configJson, BackupCrypto.inflate(deflated))
        assertTrue("deflated (${deflated.size}B) should be smaller than raw (${configJson.size}B)", deflated.size < configJson.size)
    }

    @Test
    fun `wrong pin fails with BackupCryptoException`() {
        val encrypted = BackupCrypto.encrypt(payload, "123")

        assertThrows(BackupCryptoException::class.java) {
            BackupCrypto.decrypt(encrypted, "456")
        }
    }

    @Test
    fun `tampered ciphertext fails`() {
        val encrypted = BackupCrypto.encrypt(payload, "123")
        encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1].toInt() xor 0x01).toByte()

        assertThrows(BackupCryptoException::class.java) {
            BackupCrypto.decrypt(encrypted, "123")
        }
    }

    @Test
    fun `malformed container fails`() {
        assertThrows(BackupCryptoException::class.java) {
            BackupCrypto.decrypt(ByteArray(10), "123")
        }
        assertThrows(BackupCryptoException::class.java) {
            BackupCrypto.decrypt("NOTCBX1-not-a-backup-at-all".toByteArray(), "123")
        }
    }

    @Test
    fun `each encryption uses fresh salt and iv`() {
        val a = BackupCrypto.encrypt(payload, "123")
        val b = BackupCrypto.encrypt(payload, "123")

        assertNotEquals(a.toList(), b.toList())
        assertArrayEquals(payload, BackupCrypto.decrypt(a, "123"))
        assertArrayEquals(payload, BackupCrypto.decrypt(b, "123"))
    }

    @Test
    fun `container carries magic header`() {
        val encrypted = BackupCrypto.encrypt(payload, "123")

        assertEquals("CBX1", String(encrypted.copyOfRange(0, 4), Charsets.US_ASCII))
    }

    @Test
    fun `encrypted full backup payload roundtrip`() {
        val json = """
            {"version":1,"app":"tokenusage","exportedAtEpochMs":1700000000000,
             "credentials":{"CLAUDE":{"authMode":"web_session","accessToken":"sk-ant-sid01-abc"}},
             "deepseekAccounts":[{"id":"1","label":"main","apiKey":"sk-x"}]}
        """.trimIndent().toByteArray()

        val container = BackupCrypto.encrypt(BackupCrypto.deflate(json), "999")
        assertArrayEquals(json, BackupCrypto.inflate(BackupCrypto.decrypt(container, "999")))
    }
}
