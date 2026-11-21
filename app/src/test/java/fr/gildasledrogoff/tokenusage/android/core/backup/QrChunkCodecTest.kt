package fr.gildasledrogoff.tokenusage.core.backup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
class QrChunkCodecTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val payload = BackupPayload(
        exportedAtEpochMs = 1_700_000_000_000,
        credentials = mapOf("CLAUDE" to ServiceCredentialBackup(authMode = "web_session", accessToken = "sk-ant-sid02-abc"))
    )
    private fun randomToken(length: Int): String {
        val random = java.util.Random(42)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/_-"
        return buildString {
            repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }
    private fun paddedPayload(tokenPadding: Int): BackupPayload =
        payload.copy(
            credentials = mapOf(
                "CLAUDE" to ServiceCredentialBackup(
                    authMode = "web_session",
                    accessToken = "sk-ant-sid02-" + randomToken(tokenPadding)
                )
            )
        )
    private fun bigPayload(pin: String, tokenPadding: Int = 1200): String {
        val container = BackupCrypto.encrypt(
            BackupCrypto.deflate(json.encodeToString(paddedPayload(tokenPadding)).toByteArray()),
            pin
        )
        return Base64.getEncoder().encodeToString(container)
    }
    private fun parseAll(contents: List<String>): List<QrChunkCodec.Chunk> =
        contents.map { QrChunkCodec.parseChunk(it) }
    private fun variant(parts: List<String>, replacements: List<String?>): String =
        parts.mapIndexed { i, p -> replacements.getOrNull(i) ?: p }.joinToString(":")
    @Test
    fun `short payload stays in legacy single qr format`() {
        val short = bigPayload("4242", tokenPadding = 100)
        assertTrue(short.length <= QrChunkCodec.MULTI_THRESHOLD_CHARS)
        val contents = QrChunkCodec.encode(short)
        assertEquals(listOf(short), contents)
        assertFalse(QrChunkCodec.isMultiQr(contents.first()))
        assertEquals(short, Base64.getEncoder().encodeToString(BackupCodec.parseQrPayload(short)))
    }
    @Test
    fun `large payload is split into ordered chunks of at most 600 chars`() {
        val big = bigPayload("4242")
        assertTrue(big.length > QrChunkCodec.MULTI_THRESHOLD_CHARS)
        val contents = QrChunkCodec.encode(big)
        assertTrue(contents.size in 2..QrChunkCodec.MAX_QR_PARTS)
        contents.forEachIndexed { i, code ->
            assertTrue(QrChunkCodec.isMultiQr(code))
            assertTrue(code.length <= QrChunkCodec.MAX_QR_CONTENT_CHARS)
            val chunk = QrChunkCodec.parseChunk(code)
            assertEquals(i + 1, chunk.index)
            assertEquals(contents.size, chunk.total)
            assertTrue(chunk.data.length <= QrChunkCodec.CHUNK_CHARS)
        }
    }
    @Test
    fun `payload just above the threshold still keeps every qr within 640 chars`() {
        val worstHeader = "TUQR1:32:32:" + "0".repeat(16) + ":" + "0".repeat(8) + ":"
        val edge = "A".repeat(QrChunkCodec.MAX_QR_PARTS * QrChunkCodec.CHUNK_CHARS - 1)
        val contents = QrChunkCodec.encode(edge)
        assertEquals(QrChunkCodec.MAX_QR_PARTS, contents.size)
        assertTrue(contents.last().length <= QrChunkCodec.MAX_QR_CONTENT_CHARS)
        assertTrue(worstHeader.length + 1 <= QrChunkCodec.MAX_QR_CONTENT_CHARS)
        assertEquals(edge, QrChunkCodec.reassemble(parseAll(contents)))
    }
    @Test
    fun `chunks scanned in any order with duplicates reassemble to the original`() {
        val big = bigPayload("4242")
        val contents = QrChunkCodec.encode(big)
        val shuffled = contents.asReversed() + contents.first()
        val byIndex = LinkedHashMap<Int, QrChunkCodec.Chunk>()
        shuffled.forEach { code ->
            val chunk = QrChunkCodec.parseChunk(code)
            byIndex[chunk.index] = chunk
        }
        assertEquals(big, QrChunkCodec.reassemble(byIndex.values))
    }
    @Test
    fun `reassembled multi qr payload decrypts end to end`() {
        val expected = paddedPayload(1200)
        val assembled = QrChunkCodec.reassemble(parseAll(QrChunkCodec.encode(bigPayload("4242"))))
        val decoded = BackupCodec.decodePayload(
            BackupCrypto.inflate(BackupCrypto.decrypt(BackupCodec.parseQrPayload(assembled), "4242")),
            json
        )
        assertEquals(expected, decoded)
    }
    @Test
    fun `missing chunk is detected`() {
        val contents = QrChunkCodec.encode(bigPayload("4242"))
        val chunks = parseAll(contents.dropLast(1))
        assertThrows(InvalidBackupException::class.java) { QrChunkCodec.reassemble(chunks) }
    }
    @Test
    fun `chunks from two different exports are rejected`() {
        val exportA = parseAll(QrChunkCodec.encode(bigPayload("4242")))
        val exportB = parseAll(QrChunkCodec.encode(bigPayload("999999")))
        assertThrows(InvalidBackupException::class.java) { QrChunkCodec.reassemble(exportA + exportB.last()) }
    }
    @Test
    fun `corrupted chunk data is rejected at parse time`() {
        val content = QrChunkCodec.encode(bigPayload("4242")).first()
        val replacement = if (content.endsWith("AA")) "BB" else "AA"
        val corrupted = content.dropLast(2) + replacement
        assertThrows(InvalidBackupException::class.java) { QrChunkCodec.parseChunk(corrupted) }
    }
    @Test
    fun `malformed chunk codes are rejected`() {
        val valid = QrChunkCodec.encode(bigPayload("4242")).first()
        val parts = valid.split(":")
        val rejects = listOf(
            valid.removePrefix("TUQR1"),
            "TUQR2:" + valid.removePrefix("TUQR1:"),
            variant(parts, listOf(null, "0")),
            variant(parts, listOf(null, "999")),
            variant(parts, listOf(null, null, "abc")),
            variant(parts, listOf(null, null, null, "0123456789abcdef0")),
            variant(parts, listOf(null, null, null, "zzzzzzzzzzzzzzzz")),
            variant(parts, listOf(null, null, null, null, "deadbeef0")),
            variant(parts, listOf(null, null, null, null, null, "")),
            variant(parts, listOf(null, null, null, null, null, "x".repeat(QrChunkCodec.CHUNK_CHARS + 1))),
            variant(parts, listOf(null, null, null, null, null, "not-base64!!!")),
            parts.dropLast(1).joinToString(":")
        )
        rejects.forEach { code ->
            assertThrows("should reject: $code", InvalidBackupException::class.java) {
                QrChunkCodec.parseChunk(code)
            }
        }
    }
    @Test
    fun `total above the part cap is rejected and oversized exports fail`() {
        val valid = QrChunkCodec.encode(bigPayload("4242")).first()
        val parts = valid.split(":")
        val oversizedTotal = variant(parts, listOf(null, null, "999"))
        assertThrows(InvalidBackupException::class.java) { QrChunkCodec.parseChunk(oversizedTotal) }
        val huge = bigPayload("4242", tokenPadding = 40_000)
        assertThrows(QrPayloadTooLargeException::class.java) { QrChunkCodec.encode(huge) }
    }
    @Test
    fun `chunks bind to their own export only`() {
        val firstBig = bigPayload("4242")
        val first = parseAll(QrChunkCodec.encode(firstBig))
        assertEquals(firstBig, QrChunkCodec.reassemble(first))
        val other = parseAll(QrChunkCodec.encode(bigPayload("4242", tokenPadding = 1100)))
        assertTrue(first.first().fullHash != other.first().fullHash)
    }
}
