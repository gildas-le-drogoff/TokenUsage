package fr.gildasledrogoff.tokenusage.core.backup

import android.util.Base64
import fr.gildasledrogoff.tokenusage.core.database.BalanceSnapshotDao
import fr.gildasledrogoff.tokenusage.core.database.BalanceSnapshotEntity
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.DisplayCurrency
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
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
        val accounts = AiService.entries
            .associate { service ->
                service.name to prefs.getAccounts(service).map { account ->
                    ServiceAccountBackup(
                        id = account.id,
                        label = account.label,
                        credential = BackupCodec.toBackup(account.credential),
                        orgId = (account.credential as? Credential.ClaudeWebCredential)
                            ?.let { prefs.getClaudeOrgId(account.id) }
                    )
                }
            }
            .filterValues { it.isNotEmpty() }

        val history = if (includeHistory) {
            balanceSnapshotDao.allSnapshots().map { it.toBackup() }
        } else {
            emptyList()
        }

        return BackupPayload(
            exportedAtEpochMs = System.currentTimeMillis(),
            accounts = accounts,
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
        return Base64.encodeToString(container, Base64.NO_WRAP)
    }

    suspend fun createQrCodes(pin: String): List<String> =
        QrChunkCodec.encode(createQrPayload(pin))

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
        val restored = restoreAccounts(payload)

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

        return ImportSummary(accounts = restored, historyEntries = historyAdded)
    }


    /** Merges every account found in [payload] into the stored ones; returns how many per service. */
    private fun restoreAccounts(payload: BackupPayload): Map<AiService, Int> {
        val restored = LinkedHashMap<AiService, Int>()
        for ((service, imported) in BackupCodec.importedAccounts(payload)) {
            prefs.saveAccounts(
                service,
                BackupCodec.mergeAccounts(
                    existing = prefs.getAccounts(service),
                    imported = imported.map { it.account }
                )
            )
            for ((account, orgId) in imported) {
                if (account.credential is Credential.ClaudeWebCredential && !orgId.isNullOrBlank()) {
                    prefs.saveClaudeOrgId(account.id, orgId)
                }
            }
            restored[service] = imported.map { it.account.credential.accessToken.trim() }.distinct().size
        }
        return restored
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


        internal const val AUTH_MODE_OAUTH = "oauth"
        internal const val AUTH_MODE_WEB_SESSION = "web_session"

    }
}


data class ImportSummary(
    /** Accounts imported, per service. */
    val accounts: Map<AiService, Int>,
    val historyEntries: Int
) {
    val services: List<AiService> get() = accounts.keys.toList()

    val credentialCount: Int get() = accounts.values.sum()
}


class QrPayloadTooLargeException : Exception(
    "Configuration too large for a QR code — use the encrypted ZIP export instead."
)
