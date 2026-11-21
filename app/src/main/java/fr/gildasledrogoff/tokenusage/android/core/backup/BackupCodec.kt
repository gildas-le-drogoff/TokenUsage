package fr.gildasledrogoff.tokenusage.core.backup

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
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
        return when (backup.type) {
            CredentialTypes.CLAUDE_WEB -> Credential.ClaudeWebCredential(accessToken = backup.accessToken)
            CredentialTypes.CLAUDE_OAUTH -> claudeOAuth(backup)
            CredentialTypes.CODEX -> codex(backup)
            CredentialTypes.GEMINI -> gemini(backup)
            CredentialTypes.API_KEY -> Credential.ApiKeyCredential(accessToken = backup.accessToken)
            else -> legacyCredential(service, backup)
        }
    }

    /** Files written before v4 carry no credential type: it is implied by the service. */
    private fun legacyCredential(service: AiService, backup: ServiceCredentialBackup): Credential? = when (service) {
        AiService.CLAUDE ->
            if (backup.authMode == ConfigBackupManager.AUTH_MODE_WEB_SESSION) {
                Credential.ClaudeWebCredential(accessToken = backup.accessToken)
            } else {
                claudeOAuth(backup)
            }
        AiService.CODEX -> codex(backup)
        AiService.GEMINI -> gemini(backup)
        AiService.COPILOT, AiService.OPENROUTER, AiService.OPENAI ->
            Credential.ApiKeyCredential(accessToken = backup.accessToken)
        AiService.DEEPSEEK, AiService.ZAI -> null
    }

    private fun claudeOAuth(backup: ServiceCredentialBackup) = Credential.ClaudeCredential(
        accessToken = backup.accessToken,
        refreshToken = backup.refreshToken?.takeIf { it.isNotBlank() },
        expiresAt = backup.expiresAtEpochSecond?.let(Instant::ofEpochSecond),
        scopes = backup.scopes,
        rateLimitTier = backup.rateLimitTier
    )

    private fun codex(backup: ServiceCredentialBackup): Credential? {
        val refresh = backup.refreshToken?.takeIf { it.isNotBlank() } ?: return null
        return Credential.CodexCredential(
            accessToken = backup.accessToken,
            refreshToken = refresh,
            accountId = backup.accountId?.takeIf { it.isNotBlank() }
        )
    }

    private fun gemini(backup: ServiceCredentialBackup): Credential? {
        val refresh = backup.refreshToken?.takeIf { it.isNotBlank() } ?: return null
        val clientId = backup.oauthClientId?.takeIf { it.isNotBlank() } ?: return null
        val clientSecret = backup.oauthClientSecret?.takeIf { it.isNotBlank() } ?: return null
        return Credential.GeminiCredential(
            accessToken = backup.accessToken,
            refreshToken = refresh,
            expiresAtMs = backup.expiresAtMs ?: 0L,
            oauthClientId = clientId,
            oauthClientSecret = clientSecret
        )
    }

    /** Backup form of a credential, typed so that restoring needs no knowledge of the service. */
    fun toBackup(credential: Credential): ServiceCredentialBackup = when (credential) {
        is Credential.ClaudeCredential -> ServiceCredentialBackup(
            type = CredentialTypes.CLAUDE_OAUTH,
            authMode = ConfigBackupManager.AUTH_MODE_OAUTH,
            accessToken = credential.accessToken,
            refreshToken = credential.refreshToken,
            expiresAtEpochSecond = credential.expiresAt?.epochSecond,
            scopes = credential.scopes,
            rateLimitTier = credential.rateLimitTier
        )
        is Credential.ClaudeWebCredential -> ServiceCredentialBackup(
            type = CredentialTypes.CLAUDE_WEB,
            authMode = ConfigBackupManager.AUTH_MODE_WEB_SESSION,
            accessToken = credential.accessToken
        )
        is Credential.CodexCredential -> ServiceCredentialBackup(
            type = CredentialTypes.CODEX,
            accessToken = credential.accessToken,
            refreshToken = credential.refreshToken,
            accountId = credential.accountId
        )
        is Credential.GeminiCredential -> ServiceCredentialBackup(
            type = CredentialTypes.GEMINI,
            accessToken = credential.accessToken,
            refreshToken = credential.refreshToken,
            expiresAtMs = credential.expiresAtMs,
            oauthClientId = credential.oauthClientId,
            oauthClientSecret = credential.oauthClientSecret
        )
        is Credential.DeepseekCredential,
        is Credential.ZaiCredential,
        is Credential.ApiKeyCredential ->
            ServiceCredentialBackup(type = CredentialTypes.API_KEY, accessToken = credential.accessToken)
    }

    /**
     * Every account contained in [payload], per service: the v4 `accounts` map plus the legacy
     * sections of older files, so any backup version can be imported.
     */
    fun importedAccounts(payload: BackupPayload): Map<AiService, List<ImportedAccount>> {
        val result = LinkedHashMap<AiService, MutableList<ImportedAccount>>()
        fun add(service: AiService, account: ServiceAccount, orgId: String? = null) {
            result.getOrPut(service) { mutableListOf() } += ImportedAccount(account, orgId)
        }

        for ((serviceName, entries) in payload.accounts) {
            val service = AiService.entries.firstOrNull { it.name == serviceName } ?: continue
            for (entry in entries) {
                val credential = toCredential(service, entry.credential) ?: continue
                add(service, ServiceAccount(entry.id, entry.label, credential), entry.orgId)
            }
        }

        // v1-v3: one credential per service...
        for ((serviceName, backup) in payload.credentials) {
            val service = AiService.entries.firstOrNull { it.name == serviceName } ?: continue
            if (service == AiService.CLAUDE || service == AiService.DEEPSEEK || service == AiService.ZAI) continue
            val credential = toCredential(service, backup) ?: continue
            add(service, ServiceAccount(ServiceAccount.newId(), "", credential))
        }
        // ... and the multi-account services in their own sections.
        val claude = payload.claudeAccounts.mapNotNull { backup ->
            toCredential(AiService.CLAUDE, backup.credential)
                ?.let { ImportedAccount(ServiceAccount(backup.id, backup.label, it), backup.orgId) }
        }.ifEmpty {
            listOfNotNull(
                payload.credentials[AiService.CLAUDE.name]
                    ?.let { toCredential(AiService.CLAUDE, it) }
                    ?.let { ImportedAccount(ServiceAccount(ServiceAccount.newId(), "", it), payload.claudeOrgId) }
            )
        }
        claude.forEach { add(AiService.CLAUDE, it.account, it.orgId) }
        payload.deepseekAccounts.filter { it.apiKey.isNotBlank() }.forEach {
            add(AiService.DEEPSEEK, ServiceAccount(it.id, it.label, Credential.DeepseekCredential(it.apiKey)))
        }
        payload.zaiAccounts.filter { it.apiKey.isNotBlank() }.forEach {
            add(AiService.ZAI, ServiceAccount(it.id, it.label, Credential.ZaiCredential(it.apiKey)))
        }
        return result
    }

    /**
     * Accounts after an import: an imported account replaces the existing one with the same id
     * or the same secret; all the others are kept.
     */
    fun mergeAccounts(existing: List<ServiceAccount>, imported: List<ServiceAccount>): List<ServiceAccount> {
        val importedIds = imported.mapTo(HashSet()) { it.id }
        val importedKeys = imported.mapTo(HashSet()) { it.credential.accessToken.trim() }
        val kept = existing.filterNot { it.id in importedIds || it.credential.accessToken.trim() in importedKeys }
        return kept + imported.distinctBy { it.credential.accessToken.trim() }
    }
}


/** An account read from a backup, with the Claude organisation it was cached with. */
internal data class ImportedAccount(val account: ServiceAccount, val orgId: String?)
