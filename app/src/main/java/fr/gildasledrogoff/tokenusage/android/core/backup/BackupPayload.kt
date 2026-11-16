package fr.gildasledrogoff.tokenusage.core.backup

import kotlinx.serialization.Serializable


@Serializable
data class BackupPayload(
    val version: Int = CURRENT_VERSION,
    val app: String = APP_ID,
    val exportedAtEpochMs: Long,
    
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
        const val CURRENT_VERSION = 3
        const val APP_ID = "tokenusage"
    }
}


@Serializable
data class ServiceCredentialBackup(
    
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
