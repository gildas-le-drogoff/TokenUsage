package fr.gildasledrogoff.tokenusage.core.network.claude
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
object ClaudeDto {
    @Serializable
    data class OAuthUsageResponse(
        @SerialName("five_hour") val fiveHour: OAuthUsageWindow? = null,
        @SerialName("seven_day") val sevenDay: OAuthUsageWindow? = null,
        @SerialName("seven_day_oauth_apps") val sevenDayOauthApps: OAuthUsageWindow? = null,
        @SerialName("seven_day_opus") val sevenDayOpus: OAuthUsageWindow? = null,
        @SerialName("seven_day_sonnet") val sevenDaySonnet: OAuthUsageWindow? = null,
        @SerialName("seven_day_cowork") val sevenDayCowork: OAuthUsageWindow? = null,
        @SerialName("seven_day_omelette") val sevenDayOmelette: OAuthUsageWindow? = null,
        @SerialName("omelette_promotional") val omelettePromotional: OAuthUsageWindow? = null,
        @SerialName("iguana_necktie") val iguanaNecktie: OAuthUsageWindow? = null,
        @SerialName("extra_usage") val extraUsage: OAuthExtraUsage? = null,
        val limits: List<OAuthLimit> = emptyList(),
        @SerialName("seven_day_breakdown") val sevenDayBreakdown: SevenDayBreakdown? = null
    )
    @Serializable
    data class OAuthUsageWindow(
        val utilization: Double? = null,
        @SerialName("resets_at") val resetsAt: String? = null,
        @SerialName("locked_reason") val lockedReason: String? = null
    )
    @Serializable
    data class OAuthLimit(
        val kind: String? = null,
        val group: String? = null,
        val percent: Double = 0.0,
        @SerialName("resets_at") val resetsAt: String? = null,
        val severity: String? = null,
        @SerialName("is_active") val isActive: Boolean = false,
        val scope: OAuthLimitScope? = null
    )
    @Serializable
    data class OAuthLimitScope(
        val model: OAuthLimitScopeItem? = null,
        val surface: OAuthLimitScopeItem? = null
    )
    @Serializable
    data class OAuthLimitScopeItem(
        @SerialName("display_name") val displayName: String? = null,
        val id: String? = null
    )
    @Serializable
    data class SevenDayBreakdown(
        @SerialName("as_of") val asOf: String? = null,
        @SerialName("window_started_at") val windowStartedAt: String? = null,
        val rows: List<BreakdownRow> = emptyList()
    )
    @Serializable
    data class BreakdownRow(
        val key: String? = null,
        @SerialName("display_name") val displayName: String? = null,
        val percent: Double = 0.0
    )
    @Serializable
    data class OAuthExtraUsage(
        @SerialName("is_enabled") val isEnabled: Boolean = false,
        @SerialName("monthly_limit") val monthlyLimit: Double = 0.0,
        @SerialName("used_credits") val usedCredits: Double = 0.0,
        val utilization: Double = 0.0,
        val currency: String = "USD"
    )
    @Serializable
    data class WebOrganization(
        val uuid: String? = null,
        val id: String? = null,
        val name: String? = null,
        val capabilities: List<String> = emptyList()
    ) {
        val orgId: String
            get() = uuid?.takeIf { it.isNotBlank() } ?: id.orEmpty()
        fun hasCapability(capability: String): Boolean = capabilities.any { it == capability }
    }
    @Serializable
    data class TokenRefreshResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Int,
        @SerialName("token_type") val tokenType: String? = null
    )
}
