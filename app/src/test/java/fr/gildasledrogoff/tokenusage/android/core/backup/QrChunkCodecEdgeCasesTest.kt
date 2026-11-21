package fr.gildasledrogoff.tokenusage.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Compléments à [QrChunkCodecTest] : limites exactes de découpage, désordre
 * et doublons, mélange de sessions, en-têtes corrompus, chunks fabriqués.
 */
class QrChunkCodecEdgeCasesTest {

    private val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun base64Like(length: Int, seed: Long = 1): String {
        val random = Random(seed)
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun parseAll(payload: String) = QrChunkCodec.encode(payload).map(QrChunkCodec::parseChunk)

    private fun rejects(block: () -> Unit): InvalidBackupException =
        assertThrows(InvalidBackupException::class.java) { block() }

    // --- Limites de taille ---

    @Test
    fun `payload up to the threshold stays a single raw qr`() {
        val atThreshold = base64Like(QrChunkCodec.MULTI_THRESHOLD_CHARS)

        assertEquals(listOf(atThreshold), QrChunkCodec.encode(atThreshold))
        assertEquals(listOf(""), QrChunkCodec.encode(""))
    }

    @Test
    fun `payload one char above the threshold is split in two`() {
        val payload = base64Like(QrChunkCodec.MULTI_THRESHOLD_CHARS + 1)

        val chunks = parseAll(payload)
        assertEquals(2, chunks.size)
        assertEquals(QrChunkCodec.CHUNK_CHARS, chunks[0].data.length)
        assertEquals(QrChunkCodec.MULTI_THRESHOLD_CHARS + 1 - QrChunkCodec.CHUNK_CHARS, chunks[1].data.length)
        assertEquals(payload, QrChunkCodec.reassemble(chunks))
    }

    @Test
    fun `maximum payload fits exactly in max parts and one more char is refused`() {
        val max = QrChunkCodec.MAX_QR_PARTS * QrChunkCodec.CHUNK_CHARS

        assertEquals(QrChunkCodec.MAX_QR_PARTS, QrChunkCodec.encode(base64Like(max)).size)
        assertThrows(QrPayloadTooLargeException::class.java) { QrChunkCodec.encode(base64Like(max + 1)) }
    }

    @Test
    fun `roundtrip holds for many payload sizes around chunk boundaries`() {
        val sizes = listOf(641, 1199, 1200, 1201, 1800, 6001, 12_345, 19_199, 19_200)

        sizes.forEach { size ->
            val payload = base64Like(size, seed = size.toLong())
            assertEquals("taille $size", payload, QrChunkCodec.reassemble(parseAll(payload)))
        }
    }

    @Test
    fun `every encoded chunk is recognised as multi qr and fits the qr content limit`() {
        QrChunkCodec.encode(base64Like(10_000)).forEach {
            assertTrue(QrChunkCodec.isMultiQr(it))
            assertTrue(it.length <= QrChunkCodec.MAX_QR_CONTENT_CHARS)
        }
    }

    @Test
    fun `isMultiQr only matches the exact prefix`() {
        assertFalse(QrChunkCodec.isMultiQr(""))
        assertFalse(QrChunkCodec.isMultiQr("TUQR1"))
        assertFalse(QrChunkCodec.isMultiQr("tuqr1:1:1"))
        assertFalse(QrChunkCodec.isMultiQr(" TUQR1:1:1"))
        assertTrue(QrChunkCodec.isMultiQr("TUQR1:anything"))
    }

    // --- Ordre, doublons, sessions ---

    @Test
    fun `reassemble ignores arrival order`() {
        val payload = base64Like(5_000)
        val chunks = parseAll(payload)

        assertEquals(payload, QrChunkCodec.reassemble(chunks.shuffled(Random(11))))
        assertEquals(payload, QrChunkCodec.reassemble(chunks.reversed()))
    }

    @Test
    fun `duplicate chunks passed as a raw list are rejected`() {
        val chunks = parseAll(base64Like(3_000))

        // Une liste avec doublon (et donc un morceau manquant) ne doit jamais être acceptée.
        val withDuplicate = chunks.dropLast(1) + chunks.first()
        rejects { QrChunkCodec.reassemble(withDuplicate) }
        // Trop de morceaux : même contenu complet + un doublon.
        rejects { QrChunkCodec.reassemble(chunks + chunks.first()) }
    }

    @Test
    fun `reassemble rejects an empty collection`() {
        assertEquals("No QR chunks", rejects { QrChunkCodec.reassemble(emptyList()) }.message)
    }

    @Test
    fun `single chunk of an incomplete set is rejected`() {
        val chunks = parseAll(base64Like(3_000))

        rejects { QrChunkCodec.reassemble(listOf(chunks[1])) }
    }

    @Test
    fun `chunks from another session with the same part count are rejected`() {
        val a = parseAll(base64Like(3_000, seed = 1))
        val b = parseAll(base64Like(3_000, seed = 2))
        assertEquals(a.size, b.size)

        val mixed = listOf(a[0], b[1]) + a.drop(2)
        assertEquals("QR chunks from different exports", rejects { QrChunkCodec.reassemble(mixed) }.message)
    }

    @Test
    fun `chunks announcing a different total are rejected`() {
        val a = parseAll(base64Like(3_000))
        val tampered = a.mapIndexed { i, c -> if (i == 0) c.copy(total = c.total + 1) else c }

        rejects { QrChunkCodec.reassemble(tampered) }
    }

    @Test
    fun `reassemble re-verifies the checksum of chunks built by hand`() {
        val chunks = parseAll(base64Like(3_000))
        val forged = chunks.mapIndexed { i, c -> if (i == 1) c.copy(data = "AAAA") else c }

        assertEquals("Corrupted QR chunk", rejects { QrChunkCodec.reassemble(forged) }.message)
    }

    @Test
    fun `reassemble detects a payload hash mismatch even when every chunk checksum is valid`() {
        val chunks = parseAll(base64Like(3_000))
        val forgedData = "AAAA"
        val forged = chunks.mapIndexed { i, c ->
            if (i == 1) c.copy(data = forgedData, chunkHash = QrChunkCodec.sha256Hex(forgedData).take(8)) else c
        }

        assertEquals("Reassembled payload mismatch", rejects { QrChunkCodec.reassemble(forged) }.message)
    }

    // --- En-têtes corrompus ---

    private fun validCode(): String = QrChunkCodec.encode(base64Like(3_000)).first()

    @Test
    fun `parseChunk accepts base64 padding`() {
        val data = "QUJD" + "RA=="
        val code = listOf(
            "TUQR1", "1", "1", "0".repeat(16), QrChunkCodec.sha256Hex(data).take(8), data
        ).joinToString(":")

        assertEquals(data, QrChunkCodec.parseChunk(code).data)
    }

    @Test
    fun `parseChunk rejects non numeric, negative and out of range indexes`() {
        val parts = validCode().split(":")
        fun with(index: Int, value: String) = parts.toMutableList().also { it[index] = value }.joinToString(":")

        listOf(
            with(1, "x"), with(1, "-1"), with(1, "0"), with(1, "99"), with(1, ""), with(1, " 1"),
            with(2, "x"), with(2, "0"), with(2, "-3"), with(2, "33"), with(2, ""),
            with(1, (parts[2].toInt() + 1).toString())
        ).forEach { code -> rejects { QrChunkCodec.parseChunk(code) } }
    }

    @Test
    fun `parseChunk rejects uppercase hashes and wrong lengths`() {
        val parts = validCode().split(":")
        fun with(index: Int, value: String) = parts.toMutableList().also { it[index] = value }.joinToString(":")

        listOf(
            with(3, parts[3].uppercase()),
            with(3, parts[3].dropLast(1)),
            with(4, parts[4].uppercase()),
            with(4, parts[4].dropLast(1)),
            with(4, "")
        ).forEach { code -> rejects { QrChunkCodec.parseChunk(code) } }
    }

    @Test
    fun `parseChunk rejects extra separators, blanks and noise`() {
        val valid = validCode()

        listOf("", ":", "TUQR1", "TUQR1:1:1", "$valid:extra", "$valid\n", " $valid", "random text", "TUQR1::::::")
            .forEach { code -> rejects { QrChunkCodec.parseChunk(code) } }
    }

    @Test
    fun `parseChunk rejects data with characters outside base64`() {
        val parts = validCode().split(":")
        listOf("abc def", "abc\ndef", "abc-def", "abc_def", "abc=def", "==", "é").forEach { data ->
            val code = parts.toMutableList().also {
                it[5] = data
                it[4] = QrChunkCodec.sha256Hex(data).take(8)
            }.joinToString(":")
            rejects { QrChunkCodec.parseChunk(code) }
        }
    }

    @Test
    fun `parseChunk is not fooled by a valid checksum on the wrong data`() {
        val parts = validCode().split(":")
        val other = base64Like(100, seed = 99)
        val code = parts.toMutableList().also { it[5] = other }.joinToString(":")

        assertEquals("Corrupted QR chunk", rejects { QrChunkCodec.parseChunk(code) }.message)
    }

    @Test
    fun `invalid backup errors report a not a backup reason`() {
        val error = rejects { QrChunkCodec.parseChunk("garbage") }

        assertEquals(InvalidBackupException.Reason.NOT_A_BACKUP, error.reason)
    }
}
