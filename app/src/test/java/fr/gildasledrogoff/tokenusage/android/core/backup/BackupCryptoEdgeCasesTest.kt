package fr.gildasledrogoff.tokenusage.core.backup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class BackupCryptoEdgeCasesTest {
    private val password = "correct horse"
    private val payload = """{"k":"v"}""".toByteArray()
    private val magicSize = 4
    private val saltSize = 16
    private val ivSize = 12
    private val tagSize = 16
    private val headerSize = magicSize + saltSize + ivSize
    private fun flip(container: ByteArray, index: Int): ByteArray =
        container.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
    @Test
    fun `container length is header plus payload plus gcm tag`() {
        val container = BackupCrypto.encrypt(payload, password)
        assertEquals(headerSize + payload.size + tagSize, container.size)
    }
    @Test
    fun `salt and iv are unique across many encryptions`() {
        val containers = List(8) { BackupCrypto.encrypt(payload, password) }
        val salts = containers.map { it.copyOfRange(magicSize, magicSize + saltSize).toList() }.toSet()
        val ivs = containers.map { it.copyOfRange(magicSize + saltSize, headerSize).toList() }.toSet()
        assertEquals(containers.size, salts.size)
        assertEquals(containers.size, ivs.size)
    }
    @Test
    fun `same payload and password never yield the same ciphertext`() {
        val a = BackupCrypto.encrypt(payload, password)
        val b = BackupCrypto.encrypt(payload, password)
        assertFalse(a.copyOfRange(headerSize, a.size).contentEquals(b.copyOfRange(headerSize, b.size)))
    }
    @Test
    fun `different passwords produce different keys`() {
        val container = BackupCrypto.encrypt(payload, password)
        assertThrows(BackupCryptoException::class.java) { BackupCrypto.decrypt(container, "correct horsf") }
    }
    @Test
    fun `tampering with any header zone is detected`() {
        val container = BackupCrypto.encrypt(payload, password)
        listOf(magicSize, magicSize + saltSize - 1, magicSize + saltSize, headerSize - 1).forEach { index ->
            assertThrows("octet $index", BackupCryptoException::class.java) {
                BackupCrypto.decrypt(flip(container, index), password)
            }
        }
    }
    @Test
    fun `tampering with the magic is reported as not a backup`() {
        val container = BackupCrypto.encrypt(payload, password)
        val error = assertThrows(BackupCryptoException::class.java) {
            BackupCrypto.decrypt(flip(container, 0), password)
        }
        assertEquals("Not a TokenUsage backup", error.message)
    }
    @Test
    fun `unknown format version is rejected`() {
        val container = BackupCrypto.encrypt(payload, password)
        val future = container.copyOf().also { "CBX2".toByteArray().copyInto(it) }
        val error = assertThrows(BackupCryptoException::class.java) { BackupCrypto.decrypt(future, password) }
        assertEquals("Not a TokenUsage backup", error.message)
    }
    @Test
    fun `tampering with every ciphertext and tag byte is detected`() {
        val container = BackupCrypto.encrypt(payload, password)
        for (index in headerSize until container.size) {
            assertThrows("octet $index", BackupCryptoException::class.java) {
                BackupCrypto.decrypt(flip(container, index), password)
            }
        }
    }
    @Test
    fun `truncated or extended container is rejected`() {
        val container = BackupCrypto.encrypt(payload, password)
        assertThrows(BackupCryptoException::class.java) { BackupCrypto.decrypt(container.copyOf(container.size - 1), password) }
        assertThrows(BackupCryptoException::class.java) { BackupCrypto.decrypt(container + byteArrayOf(0), password) }
    }
    @Test
    fun `container no longer than the header is reported as too short`() {
        val container = BackupCrypto.encrypt(payload, password)
        for (size in listOf(0, 1, magicSize, headerSize - 1, headerSize)) {
            val error = assertThrows("taille $size", BackupCryptoException::class.java) {
                BackupCrypto.decrypt(container.copyOf(size), password)
            }
            assertEquals("Corrupted backup: too short", error.message)
        }
    }
    @Test
    fun `container with a single ciphertext byte fails authentication`() {
        val container = BackupCrypto.encrypt(payload, password).copyOf(headerSize + 1)
        val error = assertThrows(BackupCryptoException::class.java) { BackupCrypto.decrypt(container, password) }
        assertEquals("Wrong password or corrupted backup", error.message)
    }
    @Test
    fun `empty password never decrypts`() {
        val container = BackupCrypto.encrypt(payload, password)
        assertThrows(BackupCryptoException::class.java) { BackupCrypto.decrypt(container, "") }
    }
    @Test
    fun `empty payload roundtrips`() {
        val container = BackupCrypto.encrypt(ByteArray(0), password)
        assertEquals(headerSize + tagSize, container.size)
        assertArrayEquals(ByteArray(0), BackupCrypto.decrypt(container, password))
    }
    @Test
    fun `large payload roundtrips`() {
        val big = ByteArray(1_000_000).also { Random(7).nextBytes(it) }
        val container = BackupCrypto.encrypt(big, password)
        assertEquals(headerSize + big.size + tagSize, container.size)
        assertArrayEquals(big, BackupCrypto.decrypt(container, password))
    }
    @Test
    fun `very long password roundtrips`() {
        val longPassword = "p".repeat(10_000)
        assertArrayEquals(payload, BackupCrypto.decrypt(BackupCrypto.encrypt(payload, longPassword), longPassword))
    }
    @Test
    fun `trim fallback does not accept a different password`() {
        val container = BackupCrypto.encrypt(payload, "secret1")
        assertThrows(BackupCryptoException::class.java) { BackupCrypto.decrypt(container, " secret2 ") }
    }
    @Test
    fun `password validation counts code points after normalization`() {
        val decomposed = "e\u0301"
        assertFalse(BackupCrypto.isValidPassword(decomposed.repeat(5)))
        assertTrue(BackupCrypto.isValidPassword(decomposed.repeat(6)))
        assertEquals(6, BackupCrypto.MIN_PASSWORD_LENGTH)
    }
    @Test
    fun `deflate and inflate handle empty input`() {
        assertArrayEquals(ByteArray(0), BackupCrypto.inflate(BackupCrypto.deflate(ByteArray(0))))
    }
    @Test
    fun `deflate and inflate roundtrip incompressible data larger than the internal buffer`() {
        val random = ByteArray(200_000).also { Random(3).nextBytes(it) }
        assertArrayEquals(random, BackupCrypto.inflate(BackupCrypto.deflate(random)))
    }
    @Test
    fun `inflate of truncated stream fails with BackupCryptoException`() {
        val deflated = BackupCrypto.deflate(ByteArray(5_000) { (it % 251).toByte() })
        assertThrows(BackupCryptoException::class.java) {
            BackupCrypto.inflate(deflated.copyOf(deflated.size / 2))
        }
    }
    @Test
    fun `inflate of empty input fails with BackupCryptoException`() {
        assertThrows(BackupCryptoException::class.java) { BackupCrypto.inflate(ByteArray(0)) }
    }
    @Test
    fun `inflate of garbage fails with BackupCryptoException and not a raw zip exception`() {
        val garbage = ByteArray(64) { 0xFF.toByte() }
        assertThrows(BackupCryptoException::class.java) { BackupCrypto.inflate(garbage) }
    }
}
