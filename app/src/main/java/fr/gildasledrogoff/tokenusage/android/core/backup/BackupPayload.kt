package fr.gildasledrogoff.tokenusage.core.backup

import kotlinx.serialization.Serializable


@Serializable
data class BackupPayload(
    val version: Int = CURRENT_VERSION,
    val app: String = APP_ID,
    val exportedAtEpochMs: Long,
    
    /** Every account of every service, keyed by [fr.gildasledrogoff.tokenusage.core.domain.model.AiService] name (v4+). */
    val accounts: Map<String, List<ServiceAccountBackup>> = emptyMap(),

    // --- Read-only legacy sections (v1–v3 files); no longer written. ---
    val credentials: Map<String, ServiceCredentialBackup> = emptyMap(),
    val deepseekAccounts: List<AccountBackup> = emptyList(),
    val zaiAccounts: List<AccountBackup> = emptyList(),
    
    val claudeAccounts: List<ClaudeAccountBackup> = emptyList(),
    
    val claudeOrgId: String? = null,
    val refreshIntervalMinutes: Long? = null,
    val notificationsEnabled: Boolean? = null,
    
    val eurRateSource: String? = null,
    
    val manualUsdEurRate: Double? = null,
    
    val manualUsdCnyRate: Double? = null,
    
    val displayCurrency: String? = null,
    
    val zaiHideMonthlyTools: Boolean? = null,
    
    val themeMode: String? = null,
    
    val quotaDisplayMode: String? = null,
    
    val balanceHistory: List<BalanceSnapshotBackup> = emptyList()
) {
    companion object {
        const val CURRENT_VERSION = 4
        const val APP_ID = "tokenusage"
    }
}


@Serializable
data class ServiceCredentialBackup(
    /** Which credential this is (see [CredentialTypes]); null in files written before v4. */
    val type: String? = null,
    
    val authMode: String? = null,
    val accessToken: String,
    val refreshToken: String? = null,
    
    val expiresAtEpochSecond: Long? = null,
    
    val expiresAtMs: Long? = null,
    val scopes: String? = null,
    val rateLimitTier: String? = null,
    
    val accountId: String? = null,
    
    val oauthClientId: String? = null,
    val oauthClientSecret: String? = null
)


/** One account of any service (v4+). */
@Serializable
data class ServiceAccountBackup(
    val id: String,
    val label: String,
    val credential: ServiceCredentialBackup,
    /** Cached Claude organisation of a web-session account. */
    val orgId: String? = null
)


/** Values of [ServiceCredentialBackup.type]. */
object CredentialTypes {
    const val CLAUDE_OAUTH = "claude_oauth"
    const val CLAUDE_WEB = "claude_web"
    const val CODEX = "codex"
    const val GEMINI = "gemini"
    const val API_KEY = "api_key"
}


@Serializable
data class ClaudeAccountBackup(
    val id: String,
    val label: String,
    val credential: ServiceCredentialBackup,
    val orgId: String? = null
)


@Serializable
data class AccountBackup(
    val id: String,
    val label: String,
    val apiKey: String
)


@Serializable
data class BalanceSnapshotBackup(
    
    val service: String,
    val accountId: String,
    val accountLabel: String,
    val currency: String,
    val totalBalance: Double,
    val timestampEpochMs: Long
)
