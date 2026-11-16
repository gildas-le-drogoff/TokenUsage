package fr.gildasledrogoff.tokenusage.core.network.openrouter

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


object OpenRouterDto {

    @Serializable
    data class KeyResponse(val data: KeyData? = null)

    @Serializable
    data class KeyData(
        val label: String = "",
        @SerialName("is_free_tier") val isFreeTier: Boolean = false,
        @SerialName("is_management_key") val isManagementKey: Boolean = false,
        
        val limit: Double? = null,
        
        @SerialName("limit_reset") val limitReset: String? = null,
        @SerialName("limit_remaining") val limitRemaining: Double? = null,
        val usage: Double? = null,
        @SerialName("usage_daily") val usageDaily: Double? = null,
        @SerialName("usage_weekly") val usageWeekly: Double? = null,
        @SerialName("usage_monthly") val usageMonthly: Double? = null
    )

    @Serializable
    data class CreditsResponse(val data: CreditsData? = null)

    @Serializable
    data class CreditsData(
        @SerialName("total_credits") val totalCredits: Double? = null,
        @SerialName("total_usage") val totalUsage: Double? = null
    )

    const val RESET_DAILY = "daily"
    const val RESET_WEEKLY = "weekly"
    const val RESET_MONTHLY = "monthly"

    
    const val CURRENCY = "USD"

    
    const val APP_TITLE = "TokenUsage"
}
