package fr.gildasledrogoff.tokenusage.core.backup

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.ClaudeAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipInputStream


class InvalidBackupException(
    val reason: Reason,
    message: String
) : Exception(message) {
    enum class Reason { NOT_A_BACKUP, UNSUPPORTED_VERSION }
}


internal object BackupCodec {

    private val MAGIC = "CBX1".toByteArray(Charsets.US_ASCII)

    
    fun parseQrPayload(text: String): ByteArray {
        val bytes = try {
            Base64.getDecoder().decode(text.trim())
        } catch (_: IllegalArgumentException) {
            throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "Not Base64")
        }
        if (!bytes.hasMagic()) {
            throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "Not a TokenUsage backup")
        }
        return bytes
    }

    
    const val MAX_BACKUP_BYTES = 16 * 1024 * 1024

    
    fun readBounded(input: InputStream, max: Int = MAX_BACKUP_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    
    fun parseZip(zipBytes: ByteArray): ByteArray {
        val container = try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                generateSequence { zip.nextEntry }
                    .firstOrNull { entry ->
                        !entry.isDirectory &&
                            entry.name.substringAfterLast('/') == ConfigBackupManager.CONTAINED_ENTRY_NAME
                    }
                    ?.let { readBounded(zip) }
            }
        } catch (_: IOException) {
            null
        }
        if (container == null || !container.hasMagic()) {
            throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "Not a TokenUsage backup ZIP")
        }
        return container
    }

    private fun ByteArray.hasMagic(): Boolean =
        size > MAGIC.size && copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    
    fun decodePayload(jsonBytes: ByteArray, json: Json): BackupPayload {
        val payload = try {
            json.decodeFromString<BackupPayload>(jsonBytes.decodeToString())
        } catch (_: SerializationException) {
            throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "Malformed payload")
        } catch (_: IllegalArgumentException) {
            throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "Malformed payload")
        }
        if (payload.app != BackupPayload.APP_ID) {
            throw InvalidBackupException(InvalidBackupException.Reason.NOT_A_BACKUP, "Foreign payload")
        }
        if (payload.version !in 1..BackupPayload.CURRENT_VERSION) {
            throw InvalidBackupException(
                InvalidBackupException.Reason.UNSUPPORTED_VERSION,
                "Backup version ${payload.version} not supported"
            )
        }
        return payload
    }

    
    fun toCredential(service: AiService, backup: ServiceCredentialBackup): Credential? {
        if (backup.accessToken.isBlank()) return null
        return when (service) {
            AiService.CLAUDE ->
                if (backup.authMode == ConfigBackupManager.AUTH_MODE_WEB_SESSION) {
                    Credential.ClaudeWebCredential(accessToken = backup.accessToken)
                } else {
                    Credential.ClaudeCredential(
                        accessToken = backup.accessToken,
                        refreshToken = backup.refreshToken?.takeIf { it.isNotBlank() },
                        expiresAt = backup.expiresAtEpochSecond?.let(Instant::ofEpochSecond),
                        scopes = backup.scopes,
                        rateLimitTier = backup.rateLimitTier
                    )
                }
            AiService.CODEX -> {
                val refresh = backup.refreshToken?.takeIf { it.isNotBlank() } ?: return null
                Credential.CodexCredential(
                    accessToken = backup.accessToken,
                    refreshToken = refresh,
                    accountId = backup.accountId?.takeIf { it.isNotBlank() }
                )
            }
            AiService.GEMINI -> {
                val refresh = backup.refreshToken?.takeIf { it.isNotBlank() } ?: return null
                val clientId = backup.oauthClientId?.takeIf { it.isNotBlank() } ?: return null
                val clientSecret = backup.oauthClientSecret?.takeIf { it.isNotBlank() } ?: return null
                Credential.GeminiCredential(
                    accessToken = backup.accessToken,
                    refreshToken = refresh,
                    expiresAtMs = backup.expiresAtMs ?: 0L,
                    oauthClientId = clientId,
                    oauthClientSecret = clientSecret
                )
            }
            AiService.COPILOT, AiService.OPENROUTER, AiService.OPENAI ->
                Credential.ApiKeyCredential(accessToken = backup.accessToken)
            AiService.DEEPSEEK, AiService.ZAI -> null
        }
    }

    
    fun mergeAccounts(existing: List<AccountBackup>, imported: List<AccountBackup>): List<AccountBackup> {
        val importedIds = imported.mapTo(HashSet()) { it.id }
        val importedKeys = imported.mapTo(HashSet()) { it.apiKey.trim() }
        val kept = existing.filterNot { it.id in importedIds || it.apiKey.trim() in importedKeys }
        return kept + imported.distinctBy { it.apiKey.trim() }
    }

    
    fun mergeClaudeAccounts(existing: List<ClaudeAccount>, imported: List<ClaudeAccount>): List<ClaudeAccount> {
        val importedIds = imported.mapTo(HashSet()) { it.id }
        val importedKeys = imported.mapTo(HashSet()) { it.credential.accessToken.trim() }
        val kept = existing.filterNot { it.id in importedIds || it.credential.accessToken.trim() in importedKeys }
        return kept + imported.distinctBy { it.credential.accessToken.trim() }
    }
}
