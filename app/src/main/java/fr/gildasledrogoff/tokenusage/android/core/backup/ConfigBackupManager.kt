package fr.gildasledrogoff.tokenusage.core.backup

import android.util.Base64
import fr.gildasledrogoff.tokenusage.core.database.BalanceSnapshotDao
import fr.gildasledrogoff.tokenusage.core.database.BalanceSnapshotEntity
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.ClaudeAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.DeepseekAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.DisplayCurrency
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.core.domain.model.ZaiAccount
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class ConfigBackupManager @Inject constructor(
    private val prefs: EncryptedPrefsManager,
    private val balanceSnapshotDao: BalanceSnapshotDao,
    private val displayPreferences: DisplayPreferencesRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    
    private suspend fun buildPayload(includeHistory: Boolean): BackupPayload {
        val credentials = AiService.entries
            .filter { it !in MULTI_ACCOUNT_SERVICES }
            .mapNotNull { service ->
                prefs.loadCredential(service)?.let { service.name to it.toBackup() }
            }
            .toMap()

        val history = if (includeHistory) {
            balanceSnapshotDao.allSnapshots().map { it.toBackup() }
        } else {
            emptyList()
        }

        return BackupPayload(
            exportedAtEpochMs = System.currentTimeMillis(),
            credentials = credentials,
            deepseekAccounts = prefs.getDeepseekAccounts().map {
                AccountBackup(id = it.id, label = it.label, apiKey = it.apiKey)
            },
            zaiAccounts = prefs.getZaiAccounts().map {
                AccountBackup(id = it.id, label = it.label, apiKey = it.apiKey)
            },
            claudeAccounts = prefs.getClaudeAccounts().map {
                ClaudeAccountBackup(
                    id = it.id,
                    label = it.label,
                    credential = it.credential.toBackup(),
                    orgId = prefs.getClaudeOrgId(it.id)
                )
            },
            refreshIntervalMinutes = prefs.getRefreshInterval(),
            notificationsEnabled = prefs.isNotificationsEnabled(),
            eurRateSource = prefs.getFxRateSource().name,
            manualUsdEurRate = prefs.getManualUsdEurRate(),
            manualUsdCnyRate = prefs.getManualUsdCnyRate(),
            displayCurrency = prefs.getDisplayCurrency().name,
            zaiHideMonthlyTools = prefs.isZaiMonthlyToolsHidden(),
            themeMode = displayPreferences.themeMode.value.name,
            quotaDisplayMode = displayPreferences.quotaDisplayMode.value.name,
            balanceHistory = history
        )
    }

    
    suspend fun createZipBytes(pin: String): ByteArray {
        val container = encryptedContainer(buildPayload(includeHistory = true), pin)
        return ByteArrayOutputStream(container.size).use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(CONTAINED_ENTRY_NAME))
                zip.write(container)
                zip.closeEntry()
            }
            out.toByteArray()
        }
    }

    
    suspend fun createQrPayload(pin: String): String {
        val container = encryptedContainer(buildPayload(includeHistory = false), pin)
        val payload = Base64.encodeToString(container, Base64.NO_WRAP)
        if (payload.length > QR_MAX_PAYLOAD_CHARS) {
            throw QrPayloadTooLargeException()
        }
        return payload
    }

    private fun encryptedContainer(payload: BackupPayload, pin: String): ByteArray =
        BackupCrypto.encrypt(BackupCrypto.deflate(json.encodeToString(payload).toByteArray()), pin)


    
    fun parseQrPayload(text: String): ByteArray = BackupCodec.parseQrPayload(text)

    
    fun parseZip(zipBytes: ByteArray): ByteArray = BackupCodec.parseZip(zipBytes)

    
    fun readBackupFile(input: java.io.InputStream): ByteArray? = BackupCodec.readBounded(input)

    
    fun decryptContainer(container: ByteArray, pin: String): BackupPayload =
        BackupCodec.decodePayload(
            BackupCrypto.inflate(BackupCrypto.decrypt(container, pin)),
            json
        )

    
    suspend fun restore(payload: BackupPayload): ImportSummary {
        val restoredServices = mutableListOf<AiService>()
        for ((serviceName, backup) in payload.credentials) {
            val service = AiService.entries.firstOrNull { it.name == serviceName } ?: continue
            if (service in MULTI_ACCOUNT_SERVICES) continue
            val credential = BackupCodec.toCredential(service, backup) ?: continue
            prefs.deleteCredential(service)
            prefs.saveCredential(service, credential)
            restoredServices += service
        }

        val claude = restoreClaudeAccounts(payload)

        val deepseek = payload.deepseekAccounts.filter { it.apiKey.isNotBlank() }
        if (deepseek.isNotEmpty()) {
            val merged = BackupCodec.mergeAccounts(
                existing = prefs.getDeepseekAccounts().map { AccountBackup(it.id, it.label, it.apiKey) },
                imported = deepseek
            )
            prefs.saveDeepseekAccounts(merged.map { DeepseekAccount(it.id, it.label, it.apiKey) })
        }

        val zai = payload.zaiAccounts.filter { it.apiKey.isNotBlank() }
        if (zai.isNotEmpty()) {
            val merged = BackupCodec.mergeAccounts(
                existing = prefs.getZaiAccounts().map { AccountBackup(it.id, it.label, it.apiKey) },
                imported = zai
            )
            prefs.saveZaiAccounts(merged.map { ZaiAccount(it.id, it.label, it.apiKey) })
        }

        payload.refreshIntervalMinutes?.let { prefs.setRefreshInterval(it) }
        payload.notificationsEnabled?.let { prefs.setNotificationsEnabled(it) }
        payload.eurRateSource?.let { legacySource ->
            val conversionOff = legacySource == LEGACY_FX_SOURCE_OFF
            if (!conversionOff) prefs.setFxRateSource(FxRateSource.fromName(legacySource))
            if (payload.displayCurrency == null) {
                prefs.setDisplayCurrency(if (conversionOff) DisplayCurrency.ORIGINAL else DisplayCurrency.EUR)
            }
        }
        payload.displayCurrency?.let { prefs.setDisplayCurrency(DisplayCurrency.fromName(it)) }
        payload.manualUsdEurRate?.takeIf { it.isFinite() && it > 0.0 }?.let { prefs.setManualUsdEurRate(it) }
        payload.manualUsdCnyRate?.takeIf { it.isFinite() && it > 0.0 }?.let { prefs.setManualUsdCnyRate(it) }
        payload.zaiHideMonthlyTools?.let { prefs.setZaiMonthlyToolsHidden(it) }
        payload.themeMode?.let { displayPreferences.setThemeMode(ThemeMode.fromName(it)) }
        payload.quotaDisplayMode?.let { displayPreferences.setQuotaDisplayMode(QuotaDisplayMode.fromName(it)) }

        var historyAdded = 0
        if (payload.balanceHistory.isNotEmpty()) {
            val known = balanceSnapshotDao.allSnapshots()
                .mapTo(HashSet()) { Triple(it.service, it.accountId, it.timestamp) }
            for (snapshot in payload.balanceHistory) {
                if (!known.add(Triple(snapshot.service, snapshot.accountId, snapshot.timestampEpochMs))) continue
                balanceSnapshotDao.insert(
                    BalanceSnapshotEntity(
                        service = snapshot.service,
                        accountId = snapshot.accountId,
                        accountLabel = snapshot.accountLabel,
                        currency = snapshot.currency,
                        totalBalance = snapshot.totalBalance,
                        timestamp = snapshot.timestampEpochMs
                    )
                )
                historyAdded++
            }
        }

        return ImportSummary(
            services = restoredServices,
            claudeAccounts = claude,
            deepseekAccounts = deepseek.size,
            zaiAccounts = zai.size,
            historyEntries = historyAdded
        )
    }

    
    private fun restoreClaudeAccounts(payload: BackupPayload): Int {
        val imported: List<Pair<ClaudeAccount, String?>> = payload.claudeAccounts
            .mapNotNull { backup ->
                BackupCodec.toCredential(AiService.CLAUDE, backup.credential)
                    ?.let { ClaudeAccount(backup.id, backup.label, it) to backup.orgId }
            }
            .ifEmpty {
                listOfNotNull(
                    payload.credentials[AiService.CLAUDE.name]
                        ?.let { BackupCodec.toCredential(AiService.CLAUDE, it) }
                        ?.let { ClaudeAccount(UUID.randomUUID().toString(), "", it) to payload.claudeOrgId }
                )
            }
        if (imported.isEmpty()) return 0

        prefs.saveClaudeAccounts(
            BackupCodec.mergeClaudeAccounts(existing = prefs.getClaudeAccounts(), imported = imported.map { it.first })
        )
        for ((account, orgId) in imported) {
            if (account.credential is Credential.ClaudeWebCredential && !orgId.isNullOrBlank()) {
                prefs.saveClaudeOrgId(account.id, orgId)
            }
        }
        return imported.size
    }

    private fun Credential.toBackup(): ServiceCredentialBackup = when (this) {
        is Credential.ClaudeCredential -> ServiceCredentialBackup(
            authMode = AUTH_MODE_OAUTH,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochSecond = expiresAt?.epochSecond,
            scopes = scopes,
            rateLimitTier = rateLimitTier
        )
        is Credential.ClaudeWebCredential -> ServiceCredentialBackup(
            authMode = AUTH_MODE_WEB_SESSION,
            accessToken = accessToken
        )
        is Credential.CodexCredential -> ServiceCredentialBackup(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accountId = accountId
        )
        is Credential.GeminiCredential -> ServiceCredentialBackup(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMs = expiresAtMs,
            oauthClientId = oauthClientId,
            oauthClientSecret = oauthClientSecret
        )
        is Credential.DeepseekCredential -> ServiceCredentialBackup(accessToken = accessToken)
        is Credential.ZaiCredential -> ServiceCredentialBackup(accessToken = accessToken)
        is Credential.ApiKeyCredential -> ServiceCredentialBackup(accessToken = accessToken)
    }

    private fun BalanceSnapshotEntity.toBackup() =
        BalanceSnapshotBackup(
            service = service,
            accountId = accountId,
            accountLabel = accountLabel,
            currency = currency,
            totalBalance = totalBalance,
            timestampEpochMs = timestamp
        )

    companion object {
        
        const val CONTAINED_ENTRY_NAME = "tokenusage-backup.cbx"

        
        private const val LEGACY_FX_SOURCE_OFF = "OFF"

        
        const val QR_MAX_PAYLOAD_CHARS = 2900

        internal const val AUTH_MODE_OAUTH = "oauth"
        internal const val AUTH_MODE_WEB_SESSION = "web_session"

        
        private val MULTI_ACCOUNT_SERVICES = setOf(AiService.CLAUDE, AiService.DEEPSEEK, AiService.ZAI)
    }
}


data class ImportSummary(
    val services: List<AiService>,
    val claudeAccounts: Int,
    val deepseekAccounts: Int,
    val zaiAccounts: Int,
    val historyEntries: Int
) {
    
    val credentialCount: Int get() = services.size + claudeAccounts + deepseekAccounts + zaiAccounts
}


class QrPayloadTooLargeException : Exception(
    "Configuration too large for a QR code — use the encrypted ZIP export instead."
)
