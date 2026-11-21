package fr.gildasledrogoff.tokenusage.core.security

import android.content.SharedPreferences
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.ClaudeAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.DeepseekAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.DisplayCurrency
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.ZaiAccount
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton


@Serializable
private data class DeepseekAccountPref(
    val id: String,
    val label: String,
    val apiKey: String
)


@Serializable
private data class ZaiAccountPref(
    val id: String,
    val label: String,
    val apiKey: String
)


@Serializable
private data class ClaudeAccountPref(
    val id: String,
    val label: String,
    val authMode: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochSecond: Long? = null,
    val scopes: String? = null,
    val rateLimitTier: String? = null,
    val orgId: String? = null
)


/**
 * Generic account storage, one JSON list per service under `"<SERVICE>_accounts"`.
 * [type] says which [Credential] the other fields rebuild.
 */
@Serializable
private data class StoredAccountPref(
    val id: String,
    val label: String = "",
    val type: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val accountId: String? = null,
    val expiresAtMs: Long? = null,
    val oauthClientId: String? = null,
    val oauthClientSecret: String? = null,
    val hostedDomain: String? = null
)


@Serializable
data class CachedEurRate(
    val rate: Double,
    val date: String? = null,
    val fetchedAtMs: Long
)


@Singleton
class EncryptedPrefsManager @Inject constructor(
    private val storageFactory: SecureStorageFactory
) {
    private val deepseekJson = Json { ignoreUnknownKeys = true }

    private val deepseekAccountsKey = "DEEPSEEK_accounts"

    private val zaiJson = Json { ignoreUnknownKeys = true }

    private val zaiAccountsKey = "ZAI_accounts"

    private val claudeJson = Json { ignoreUnknownKeys = true }

    private val claudeAccountsKey = "CLAUDE_accounts"

    private val fxJson = Json { ignoreUnknownKeys = true }

    private val genericJson = Json { ignoreUnknownKeys = true }

    private val prefs: SharedPreferences by lazy { storageFactory.credentials }

    // ---------------------------------------------------------------------------------------
    // Accounts: every service holds N accounts. Claude, DeepSeek and Z.ai keep their historical
    // storage format (see [customStores]); all other services use the generic format.
    // ---------------------------------------------------------------------------------------

    private interface AccountStore {
        fun read(): List<ServiceAccount>
        fun write(accounts: List<ServiceAccount>)
    }

    private val claudeStore = object : AccountStore {
        override fun read() = getClaudeAccounts().map { ServiceAccount(it.id, it.label, it.credential) }
        override fun write(accounts: List<ServiceAccount>) = saveClaudeAccounts(
            accounts.filter { it.credential is Credential.ClaudeCredential || it.credential is Credential.ClaudeWebCredential }
                .map { ClaudeAccount(it.id, it.label, it.credential) }
        )
    }

    private val deepseekStore = object : AccountStore {
        override fun read() = getDeepseekAccounts()
            .map { ServiceAccount(it.id, it.label, Credential.DeepseekCredential(it.apiKey)) }
        override fun write(accounts: List<ServiceAccount>) = saveDeepseekAccounts(
            accounts.map { DeepseekAccount(it.id, it.label, it.credential.accessToken) }
        )
    }

    private val zaiStore = object : AccountStore {
        override fun read() = getZaiAccounts()
            .map { ServiceAccount(it.id, it.label, Credential.ZaiCredential(it.apiKey)) }
        override fun write(accounts: List<ServiceAccount>) = saveZaiAccounts(
            accounts.map { ZaiAccount(it.id, it.label, it.credential.accessToken) }
        )
    }

    /** Services whose accounts keep a historical storage format. */
    private val customStores: Map<AiService, AccountStore> by lazy {
        mapOf(AiService.CLAUDE to claudeStore, AiService.DEEPSEEK to deepseekStore, AiService.ZAI to zaiStore)
    }

    private fun storeFor(service: AiService): AccountStore =
        customStores[service] ?: object : AccountStore {
            override fun read() = readGenericAccounts(service)
            override fun write(accounts: List<ServiceAccount>) = writeGenericAccounts(service, accounts)
        }

    /** Accounts configured for [service], in display order. Never null, may be empty. */
    @Synchronized
    fun getAccounts(service: AiService): List<ServiceAccount> = storeFor(service).read()

    /** Replaces the whole account list of [service]. */
    @Synchronized
    fun saveAccounts(service: AiService, accounts: List<ServiceAccount>) = storeFor(service).write(accounts)

    /** Appends a new account and returns it. */
    @Synchronized
    fun addAccount(service: AiService, label: String, credential: Credential): ServiceAccount {
        val account = ServiceAccount(ServiceAccount.newId(), label, credential)
        saveAccounts(service, getAccounts(service) + account)
        return account
    }

    @Synchronized
    fun updateAccountCredential(service: AiService, accountId: String, credential: Credential) {
        val accounts = getAccounts(service)
        if (accounts.none { it.id == accountId }) return
        saveAccounts(service, accounts.map { if (it.id == accountId) it.copy(credential = credential) else it })
    }

    @Synchronized
    fun updateAccountLabel(service: AiService, accountId: String, label: String) {
        val accounts = getAccounts(service)
        if (accounts.none { it.id == accountId }) return
        saveAccounts(service, accounts.map { if (it.id == accountId) it.copy(label = label) else it })
    }

    @Synchronized
    fun deleteAccount(service: AiService, accountId: String) {
        saveAccounts(service, getAccounts(service).filterNot { it.id == accountId })
    }

    private fun readGenericAccounts(service: AiService): List<ServiceAccount> {
        val raw = prefs.getString(accountsKey(service), null) ?: return migrateLegacyAccount(service)
        return try {
            genericJson.decodeFromString<List<StoredAccountPref>>(raw).mapNotNull { it.toDomain() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeGenericAccounts(service: AiService, accounts: List<ServiceAccount>) {
        val editor = prefs.edit()
        if (accounts.isEmpty()) {
            editor.remove(accountsKey(service))
        } else {
            editor.putString(accountsKey(service), genericJson.encodeToString(accounts.mapNotNull { it.toPref() }))
        }
        editor.apply()
    }

    private fun accountsKey(service: AiService) = "${service.name}_accounts"

    /**
     * Before multi-account support a service held one credential in flat keys
     * (`<SERVICE>_access_token`, …). It becomes the first account, with nothing lost.
     */
    private fun migrateLegacyAccount(service: AiService): List<ServiceAccount> {
        val prefix = service.name
        val accessToken = prefs.getString("${prefix}_access_token", null) ?: return emptyList()
        val refreshToken = prefs.getString("${prefix}_refresh_token", null)
        val clientId = prefs.getString("${prefix}_oauth_client_id", null)
        val credential: Credential = when {
            clientId != null -> {
                val expiresAtMs = prefs.getLong("${prefix}_expires_at_ms", -1L).takeIf { it > 0 }
                val clientSecret = prefs.getString("${prefix}_oauth_client_secret", null)
                if (refreshToken == null || expiresAtMs == null || clientSecret == null) return emptyList()
                Credential.GeminiCredential(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtMs = expiresAtMs,
                    oauthClientId = clientId,
                    oauthClientSecret = clientSecret,
                    hostedDomain = prefs.getString("${prefix}_hosted_domain", null)
                )
            }
            refreshToken != null -> Credential.CodexCredential(
                accessToken = accessToken,
                refreshToken = refreshToken,
                accountId = prefs.getString("${prefix}_account_id", null)
            )
            else -> Credential.ApiKeyCredential(accessToken = accessToken)
        }
        val migrated = ServiceAccount(ServiceAccount.newId(), "", credential)
        val editor = prefs.edit()
        LEGACY_FLAT_SUFFIXES.forEach { editor.remove("${prefix}_$it") }
        editor.apply()
        writeGenericAccounts(service, listOf(migrated))
        return listOf(migrated)
    }

    private fun StoredAccountPref.toDomain(): ServiceAccount? {
        if (accessToken.isBlank()) return null
        val credential: Credential = when (type) {
            TYPE_CODEX -> Credential.CodexCredential(accessToken, refreshToken ?: return null, accountId)
            TYPE_GEMINI -> Credential.GeminiCredential(
                accessToken = accessToken,
                refreshToken = refreshToken ?: return null,
                expiresAtMs = expiresAtMs ?: return null,
                oauthClientId = oauthClientId ?: return null,
                oauthClientSecret = oauthClientSecret ?: return null,
                hostedDomain = hostedDomain
            )
            TYPE_API_KEY -> Credential.ApiKeyCredential(accessToken)
            else -> return null
        }
        return ServiceAccount(id, label, credential)
    }

    private fun ServiceAccount.toPref(): StoredAccountPref? = when (val c = credential) {
        is Credential.CodexCredential -> StoredAccountPref(
            id = id, label = label, type = TYPE_CODEX,
            accessToken = c.accessToken, refreshToken = c.refreshToken, accountId = c.accountId
        )
        is Credential.GeminiCredential -> StoredAccountPref(
            id = id, label = label, type = TYPE_GEMINI,
            accessToken = c.accessToken, refreshToken = c.refreshToken, expiresAtMs = c.expiresAtMs,
            oauthClientId = c.oauthClientId, oauthClientSecret = c.oauthClientSecret, hostedDomain = c.hostedDomain
        )
        is Credential.ApiKeyCredential -> StoredAccountPref(
            id = id, label = label, type = TYPE_API_KEY, accessToken = c.accessToken
        )
        else -> null
    }

    // --- Single-credential shortcuts: they stand for the first (primary) account. ---

    @Synchronized
    fun saveCredential(service: AiService, credential: Credential) {
        val primary = getAccounts(service).firstOrNull()
        if (primary != null) {
            updateAccountCredential(service, primary.id, credential)
        } else {
            addAccount(service, "", credential)
        }
    }

    fun loadCredential(service: AiService): Credential? = getAccounts(service).firstOrNull()?.credential

    fun deleteCredential(service: AiService) {
        val prefix = service.name
        val editor = prefs.edit()

        val keys = prefs.all.keys.filter { it.startsWith(prefix) }
        keys.forEach { editor.remove(it) }

        editor.apply()
    }

    fun deleteAllCredentials() {
        prefs.edit().clear().apply()
    }

    fun hasCredential(service: AiService): Boolean = getAccounts(service).isNotEmpty()


    fun getDeepseekAccounts(): List<DeepseekAccount> {
        val raw = prefs.getString(deepseekAccountsKey, null) ?: return emptyList()
        return try {
            deepseekJson.decodeFromString<List<DeepseekAccountPref>>(raw)
                .map { DeepseekAccount(id = it.id, label = it.label, apiKey = it.apiKey) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveDeepseekAccounts(accounts: List<DeepseekAccount>) {
        val json = deepseekJson.encodeToString(
            accounts.map { DeepseekAccountPref(it.id, it.label, it.apiKey) }
        )
        prefs.edit().putString(deepseekAccountsKey, json).apply()
    }

    fun deleteDeepseekAccount(accountId: String) {
        saveDeepseekAccounts(getDeepseekAccounts().filterNot { it.id == accountId })
    }


    fun getZaiAccounts(): List<ZaiAccount> {
        val raw = prefs.getString(zaiAccountsKey, null) ?: return emptyList()
        return try {
            zaiJson.decodeFromString<List<ZaiAccountPref>>(raw)
                .map { ZaiAccount(id = it.id, label = it.label, apiKey = it.apiKey) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveZaiAccounts(accounts: List<ZaiAccount>) {
        val json = zaiJson.encodeToString(
            accounts.map { ZaiAccountPref(it.id, it.label, it.apiKey) }
        )
        prefs.edit().putString(zaiAccountsKey, json).apply()
    }

    fun deleteZaiAccount(accountId: String) {
        saveZaiAccounts(getZaiAccounts().filterNot { it.id == accountId })
    }


    @Synchronized
    fun getClaudeAccounts(): List<ClaudeAccount> = readClaudeAccountPrefs().mapNotNull { it.toDomain() }

    
    @Synchronized
    fun saveClaudeAccounts(accounts: List<ClaudeAccount>) {
        val existing = readClaudeAccountPrefs().associateBy { it.id }
        writeClaudeAccountPrefs(
            accounts.map { account -> account.toPref(orgId = existing[account.id]?.orgIdFor(account.credential)) }
        )
    }

    
    @Synchronized
    fun updateClaudeAccountCredential(accountId: String, credential: Credential) {
        val accounts = readClaudeAccountPrefs()
        if (accounts.none { it.id == accountId }) return
        writeClaudeAccountPrefs(
            accounts.map { pref ->
                if (pref.id != accountId) pref
                else ClaudeAccount(pref.id, pref.label, credential).toPref(orgId = pref.orgIdFor(credential))
            }
        )
    }

    @Synchronized
    fun deleteClaudeAccount(accountId: String) {
        writeClaudeAccountPrefs(readClaudeAccountPrefs().filterNot { it.id == accountId })
    }

    
    @Synchronized
    fun getClaudeOrgId(accountId: String): String? =
        readClaudeAccountPrefs().firstOrNull { it.id == accountId }?.orgId?.takeIf { it.isNotBlank() }

    @Synchronized
    fun saveClaudeOrgId(accountId: String, orgId: String?) {
        val accounts = readClaudeAccountPrefs()
        if (accounts.none { it.id == accountId }) return
        writeClaudeAccountPrefs(accounts.map { if (it.id == accountId) it.copy(orgId = orgId) else it })
    }

    fun clearClaudeOrgId(accountId: String) = saveClaudeOrgId(accountId, null)

    private fun readClaudeAccountPrefs(): List<ClaudeAccountPref> {
        val raw = prefs.getString(claudeAccountsKey, null) ?: return migrateLegacyClaudeAccount()
        return try {
            claudeJson.decodeFromString<List<ClaudeAccountPref>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeClaudeAccountPrefs(accounts: List<ClaudeAccountPref>) {
        prefs.edit().putString(claudeAccountsKey, claudeJson.encodeToString(accounts)).apply()
    }

    
    private fun migrateLegacyClaudeAccount(): List<ClaudeAccountPref> {
        val accessToken = prefs.getString(LEGACY_CLAUDE_ACCESS_TOKEN, null) ?: return emptyList()
        val authMode = prefs.getString(LEGACY_CLAUDE_AUTH_MODE, null)
            ?: accessToken.takeIf(Credential.ClaudeWebCredential::isSessionKey)?.let { AUTH_MODE_WEB_SESSION }
            ?: AUTH_MODE_OAUTH
        val migrated = ClaudeAccountPref(
            id = UUID.randomUUID().toString(),
            label = "",
            authMode = authMode,
            accessToken = accessToken,
            refreshToken = prefs.getString(LEGACY_CLAUDE_REFRESH_TOKEN, null)
                .takeIf { authMode == AUTH_MODE_OAUTH },
            expiresAtEpochSecond = prefs.getLong(LEGACY_CLAUDE_EXPIRES_AT, -1L)
                .takeIf { it > 0 && authMode == AUTH_MODE_OAUTH },
            scopes = prefs.getString(LEGACY_CLAUDE_SCOPES, null),
            rateLimitTier = prefs.getString(LEGACY_CLAUDE_RATE_LIMIT_TIER, null),
            orgId = prefs.getString(LEGACY_CLAUDE_ORG_ID, null)
                ?.takeIf { it.isNotBlank() && authMode == AUTH_MODE_WEB_SESSION }
        )
        val editor = prefs.edit()
        LEGACY_CLAUDE_KEYS.forEach(editor::remove)
        editor.putString(claudeAccountsKey, claudeJson.encodeToString(listOf(migrated)))
        editor.apply()
        return listOf(migrated)
    }

    private fun ClaudeAccountPref.orgIdFor(credential: Credential): String? =
        orgId.takeIf {
            authMode == AUTH_MODE_WEB_SESSION &&
                credential is Credential.ClaudeWebCredential &&
                credential.accessToken == accessToken
        }

    private fun ClaudeAccountPref.toDomain(): ClaudeAccount? {
        if (accessToken.isBlank()) return null
        val credential = if (authMode == AUTH_MODE_WEB_SESSION) {
            Credential.ClaudeWebCredential(accessToken = accessToken)
        } else {
            Credential.ClaudeCredential(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = expiresAtEpochSecond?.takeIf { it > 0 }?.let(Instant::ofEpochSecond),
                scopes = scopes,
                rateLimitTier = rateLimitTier
            )
        }
        return ClaudeAccount(id = id, label = label, credential = credential)
    }

    private fun ClaudeAccount.toPref(orgId: String?): ClaudeAccountPref = when (val c = credential) {
        is Credential.ClaudeWebCredential -> ClaudeAccountPref(
            id = id, label = label, authMode = AUTH_MODE_WEB_SESSION, accessToken = c.accessToken, orgId = orgId
        )
        is Credential.ClaudeCredential -> ClaudeAccountPref(
            id = id,
            label = label,
            authMode = AUTH_MODE_OAUTH,
            accessToken = c.accessToken,
            refreshToken = c.refreshToken,
            expiresAtEpochSecond = c.expiresAt?.epochSecond,
            scopes = c.scopes,
            rateLimitTier = c.rateLimitTier
        )
        else -> error("Not a Claude credential")
    }

    fun getRefreshInterval(): Long {
        return prefs.getLong("refresh_interval_minutes", 30L)
    }

    fun setRefreshInterval(minutes: Long) {
        prefs.edit().putLong("refresh_interval_minutes", minutes).apply()
    }

    fun isNotificationsEnabled(): Boolean {
        return prefs.getBoolean("notifications_enabled", true)
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("notifications_enabled", enabled).apply()
    }

    
    fun isAppLockEnabled(): Boolean {
        return prefs.getBoolean(APP_LOCK_ENABLED_KEY, false)
    }

    fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(APP_LOCK_ENABLED_KEY, enabled).apply()
    }

    
    fun isZaiMonthlyToolsHidden(): Boolean {
        return prefs.getBoolean(ZAI_HIDE_MONTHLY_TOOLS_KEY, true)
    }

    fun setZaiMonthlyToolsHidden(hidden: Boolean) {
        prefs.edit().putBoolean(ZAI_HIDE_MONTHLY_TOOLS_KEY, hidden).apply()
    }


    
    fun getDisplayCurrency(): DisplayCurrency {
        prefs.getString(DISPLAY_CURRENCY_KEY, null)?.let { return DisplayCurrency.fromName(it) }
        val legacySource = prefs.getString(FX_RATE_SOURCE_KEY, null)
        return if (legacySource != null && legacySource != LEGACY_SOURCE_OFF) DisplayCurrency.EUR else DisplayCurrency.ORIGINAL
    }

    fun setDisplayCurrency(currency: DisplayCurrency) {
        prefs.edit().putString(DISPLAY_CURRENCY_KEY, currency.name).apply()
    }

    fun getFxRateSource(): FxRateSource =
        FxRateSource.fromName(prefs.getString(FX_RATE_SOURCE_KEY, null))

    fun setFxRateSource(source: FxRateSource) {
        val currency = getDisplayCurrency()
        prefs.edit()
            .putString(DISPLAY_CURRENCY_KEY, currency.name)
            .putString(FX_RATE_SOURCE_KEY, source.name)
            .apply()
    }

    
    fun isFxApiFetchAllowed(): Boolean = prefs.getBoolean(FX_API_FETCH_ALLOWED_KEY, false)

    fun setFxApiFetchAllowed(allowed: Boolean) {
        prefs.edit().putBoolean(FX_API_FETCH_ALLOWED_KEY, allowed).apply()
    }

    
    fun getManualUsdEurRate(): Double? = getManualRate(MANUAL_USD_EUR_RATE_KEY)

    fun setManualUsdEurRate(rate: Double?) = setManualRate(MANUAL_USD_EUR_RATE_KEY, rate)

    
    fun getManualUsdCnyRate(): Double? = getManualRate(MANUAL_USD_CNY_RATE_KEY)

    fun setManualUsdCnyRate(rate: Double?) = setManualRate(MANUAL_USD_CNY_RATE_KEY, rate)

    private fun getManualRate(key: String): Double? =
        prefs.getString(key, null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

    private fun setManualRate(key: String, rate: Double?) {
        val editor = prefs.edit()
        if (rate == null) editor.remove(key) else editor.putString(key, rate.toString())
        editor.apply()
    }

    
    fun getCachedEurRate(source: FxRateSource, currency: String): CachedEurRate? {
        val raw = prefs.getString(eurCacheKey(source, currency), null) ?: return null
        return runCatching { fxJson.decodeFromString<CachedEurRate>(raw) }.getOrNull()
    }

    fun saveCachedEurRate(source: FxRateSource, currency: String, cached: CachedEurRate) {
        prefs.edit().putString(eurCacheKey(source, currency), fxJson.encodeToString(cached)).apply()
    }

    private fun eurCacheKey(source: FxRateSource, currency: String) =
        "FX_cache_${source.name}_${currency.uppercase()}"

    fun saveResetTimes(service: AiService, windows: List<Pair<String, Instant?>>) {
        val editor = prefs.edit()
        windows.forEach { (label, resetsAt) ->
            val key = "${service.name}_${label}_resets_at"
            if (resetsAt != null) {
                editor.putLong(key, resetsAt.epochSecond)
            } else {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    fun loadResetTimes(service: AiService): Map<String, Instant> {
        val prefix = "${service.name}_"
        val suffix = "_resets_at"
        return prefs.all
            .filter { it.key.startsWith(prefix) && it.key.endsWith(suffix) }
            .mapNotNull { (key, value) ->
                val label = key.removePrefix(prefix).removeSuffix(suffix)
                val epochSecond = (value as? Long)?.takeIf { it > 0 } ?: return@mapNotNull null
                label to Instant.ofEpochSecond(epochSecond)
            }
            .toMap()
    }

    private companion object {
        const val TYPE_CODEX = "codex"
        const val TYPE_GEMINI = "gemini"
        const val TYPE_API_KEY = "api_key"
        val LEGACY_FLAT_SUFFIXES = listOf(
            "access_token", "refresh_token", "account_id", "expires_at_ms",
            "oauth_client_id", "oauth_client_secret", "hosted_domain"
        )
        const val AUTH_MODE_OAUTH = "oauth"
        const val AUTH_MODE_WEB_SESSION = "web_session"
        const val DISPLAY_CURRENCY_KEY = "display_currency"
        const val FX_RATE_SOURCE_KEY = "eur_rate_source"
        const val FX_API_FETCH_ALLOWED_KEY = "fx_api_fetch_allowed"
        const val MANUAL_USD_EUR_RATE_KEY = "eur_manual_usd_rate"
        const val MANUAL_USD_CNY_RATE_KEY = "fx_manual_usd_cny_rate"
        private const val LEGACY_SOURCE_OFF = "OFF"
        const val ZAI_HIDE_MONTHLY_TOOLS_KEY = "zai_hide_monthly_tools"
        const val APP_LOCK_ENABLED_KEY = "app_lock_enabled"

        const val LEGACY_CLAUDE_ACCESS_TOKEN = "CLAUDE_access_token"
        const val LEGACY_CLAUDE_REFRESH_TOKEN = "CLAUDE_refresh_token"
        const val LEGACY_CLAUDE_AUTH_MODE = "CLAUDE_auth_mode"
        const val LEGACY_CLAUDE_EXPIRES_AT = "CLAUDE_expires_at"
        const val LEGACY_CLAUDE_SCOPES = "CLAUDE_scopes"
        const val LEGACY_CLAUDE_RATE_LIMIT_TIER = "CLAUDE_rate_limit_tier"
        const val LEGACY_CLAUDE_ORG_ID = "CLAUDE_org_id"
        val LEGACY_CLAUDE_KEYS = listOf(
            LEGACY_CLAUDE_ACCESS_TOKEN, LEGACY_CLAUDE_REFRESH_TOKEN, LEGACY_CLAUDE_AUTH_MODE,
            LEGACY_CLAUDE_EXPIRES_AT, LEGACY_CLAUDE_SCOPES, LEGACY_CLAUDE_RATE_LIMIT_TIER, LEGACY_CLAUDE_ORG_ID
        )
    }
}
