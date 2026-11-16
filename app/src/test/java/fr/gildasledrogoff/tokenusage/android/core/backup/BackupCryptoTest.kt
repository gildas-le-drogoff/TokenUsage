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
    fun `pin policy requires digits only and at least 3 characters`() {
        assertFalse(BackupCrypto.isValidPin(""))
        assertFalse(BackupCrypto.isValidPin("12"))
        assertTrue(BackupCrypto.isValidPin("123"))
        assertTrue(BackupCrypto.isValidPin("0004829"))
        assertFalse(BackupCrypto.isValidPin("12a"))
        assertFalse(BackupCrypto.isValidPin("12 3"))
        assertFalse(BackupCrypto.isValidPin("1234-"))
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
