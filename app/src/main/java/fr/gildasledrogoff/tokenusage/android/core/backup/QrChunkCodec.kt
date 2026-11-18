package fr.gildasledrogoff.tokenusage.core.backup

import java.security.MessageDigest

object QrChunkCodec {

    const val MULTI_THRESHOLD_CHARS = 640
    const val MAX_QR_CONTENT_CHARS = 640
    const val CHUNK_CHARS = 600
    const val MAX_QR_PARTS = 32

    private const val PREFIX = "TUQR1"
    private const val SEPARATOR = ":"
    private const val FULL_HASH_CHARS = 16
    private const val CHUNK_HASH_CHARS = 8

    private const val BASE64_REGEX = "^[A-Za-z0-9+/]+={0,2}$"
    private val base64Chars = Regex(BASE64_REGEX)
    private val hexChars = Regex("^[0-9a-f]+$")

    data class Chunk(
        val index: Int,
        val total: Int,
        val fullHash: String,
        val chunkHash: String,
        val data: String
    )

    fun isMultiQr(text: String): Boolean = text.startsWith("$PREFIX$SEPARATOR")

    fun encode(payload: String): List<String> {
        if (payload.length <= MULTI_THRESHOLD_CHARS) return listOf(payload)
        val total = (payload.length + CHUNK_CHARS - 1) / CHUNK_CHARS
        if (total > MAX_QR_PARTS) throw QrPayloadTooLargeException()
        val fullHash = sha256Hex(payload).take(FULL_HASH_CHARS)
        return (1..total).map { index ->
            val from = (index - 1) * CHUNK_CHARS
            val data = payload.substring(from, minOf(from + CHUNK_CHARS, payload.length))
            listOf(
                PREFIX,
                index.toString(),
                total.toString(),
                fullHash,
                sha256Hex(data).take(CHUNK_HASH_CHARS),
                data
            ).joinToString(SEPARATOR)
        }
    }

    fun parseChunk(text: String): Chunk {
        val parts = text.split(SEPARATOR)
        if (parts.size != 6 || parts[0] != PREFIX) {
            throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "Malformed QR chunk header")
        }
        val index = parts[1].toIntOrNull()
            ?: throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "Malformed QR chunk index")
        val total = parts[2].toIntOrNull()
            ?: throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "Malformed QR chunk total")
        val fullHash = parts[3]
        val chunkHash = parts[4]
        val data = parts[5]
        if (total !in 1..MAX_QR_PARTS || index !in 1..total) {
            throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "QR chunk index out of range")
        }
        if (fullHash.length != FULL_HASH_CHARS || !hexChars.matches(fullHash)) {
            throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "Malformed QR chunk hash")
        }
        if (chunkHash.length != CHUNK_HASH_CHARS || !hexChars.matches(chunkHash)) {
            throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "Malformed QR chunk checksum")
        }
        if (data.isEmpty() || data.length > CHUNK_CHARS || !base64Chars.matches(data)) {
            throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "Malformed QR chunk data")
        }
        if (sha256Hex(data).take(CHUNK_HASH_CHARS) != chunkHash) {
            throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "Corrupted QR chunk")
        }
        return Chunk(index, total, fullHash, chunkHash, data)
    }

    fun reassemble(chunks: Collection<Chunk>): String {
        if (chunks.isEmpty()) {
            throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "No QR chunks")
        }
        val total = chunks.first().total
        val fullHash = chunks.first().fullHash
        if (chunks.any { it.total != total || it.fullHash != fullHash }) {
            throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "QR chunks from different exports")
        }
        if (chunks.size != total || chunks.map { it.index }.toSortedSet() != (1..total).toSortedSet()) {
            throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "Missing QR chunks")
        }
        for (chunk in chunks) {
            if (sha256Hex(chunk.data).take(CHUNK_HASH_CHARS) != chunk.chunkHash) {
                throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "Corrupted QR chunk")
            }
        }
        val payload = chunks.sortedBy { it.index }.joinToString(separator = "") { it.data }
        if (sha256Hex(payload).take(FULL_HASH_CHARS) != fullHash) {
            throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "Reassembled payload mismatch")
        }
        return payload
    }

    internal fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.US_ASCII))
            .joinToString(separator = "") { "%02x".format(it) }
}
