package fr.gildasledrogoff.tokenusage.core.security

import android.content.SharedPreferences
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.ClaudeAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.DeepseekAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.DisplayCurrency
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
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

    private val prefs: SharedPreferences by lazy { storageFactory.credentials }

    @Synchronized
    fun saveCredential(service: AiService, credential: Credential) {
        if (service == AiService.CLAUDE) {
            val primary = readClaudeAccountPrefs().firstOrNull()
            if (primary != null) {
                updateClaudeAccountCredential(primary.id, credential)
            } else {
                saveClaudeAccounts(listOf(ClaudeAccount(UUID.randomUUID().toString(), "", credential)))
            }
            return
        }

        val editor = prefs.edit()
        val prefix = service.name

        editor.putString("${prefix}_access_token", credential.accessToken)
        editor.putString("${prefix}_refresh_token", credential.refreshToken)

        when (credential) {
            is Credential.ClaudeCredential, is Credential.ClaudeWebCredential -> {
            }
            is Credential.CodexCredential -> {
                credential.accountId?.let {
                    editor.putString("${prefix}_account_id", it)
                }
            }
            is Credential.GeminiCredential -> {
                editor.putLong("${prefix}_expires_at_ms", credential.expiresAtMs)
                editor.putString("${prefix}_oauth_client_id", credential.oauthClientId)
                editor.putString("${prefix}_oauth_client_secret", credential.oauthClientSecret)
                if (credential.hostedDomain != null) {
                    editor.putString("${prefix}_hosted_domain", credential.hostedDomain)
                } else {
                    editor.remove("${prefix}_hosted_domain")
                }
            }
            is Credential.DeepseekCredential -> {
            }
            is Credential.ZaiCredential -> {
            }
            is Credential.ApiKeyCredential -> {
            }
        }

        editor.apply()
    }

    fun loadCredential(service: AiService): Credential? {
        val prefix = service.name

        if (service == AiService.DEEPSEEK) {
            return getDeepseekAccounts().firstOrNull()
                ?.let { Credential.DeepseekCredential(accessToken = it.apiKey) }
        }

        if (service == AiService.ZAI) {
            return getZaiAccounts().firstOrNull()
                ?.let { Credential.ZaiCredential(accessToken = it.apiKey) }
        }

        if (service == AiService.CLAUDE) {
            return getClaudeAccounts().firstOrNull()?.credential
        }

        val accessToken = prefs.getString("${prefix}_access_token", null) ?: return null

        return when (service) {
            AiService.CLAUDE -> null
            AiService.CODEX -> {
                val refreshToken = prefs.getString("${prefix}_refresh_token", null) ?: return null
                val accountId = prefs.getString("${prefix}_account_id", null)
                Credential.CodexCredential(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    accountId = accountId
                )
            }
            AiService.GEMINI -> {
                val refreshToken = prefs.getString("${prefix}_refresh_token", null) ?: return null
                val expiresAtMs = prefs.getLong("${prefix}_expires_at_ms", -1L)
                    .takeIf { it > 0 } ?: return null
                val clientId = prefs.getString("${prefix}_oauth_client_id", null) ?: return null
                val clientSecret = prefs.getString("${prefix}_oauth_client_secret", null) ?: return null
                Credential.GeminiCredential(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtMs = expiresAtMs,
                    oauthClientId = clientId,
                    oauthClientSecret = clientSecret,
                    hostedDomain = prefs.getString("${prefix}_hosted_domain", null)
                )
            }
            AiService.DEEPSEEK -> null
            AiService.ZAI -> null
            AiService.COPILOT, AiService.OPENROUTER, AiService.OPENAI ->
                Credential.ApiKeyCredential(accessToken = accessToken)
        }
    }

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

    fun hasCredential(service: AiService): Boolean {
        if (service == AiService.DEEPSEEK) {
            return getDeepseekAccounts().isNotEmpty()
        }
        if (service == AiService.ZAI) {
            return getZaiAccounts().isNotEmpty()
        }
        if (service == AiService.CLAUDE) {
            return getClaudeAccounts().isNotEmpty()
        }
        return prefs.getString("${service.name}_access_token", null) != null
    }


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
