package fr.gildasledrogoff.tokenusage.core.network.copilot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
object CopilotDto {
    @Serializable
    data class UserResponse(
        val login: String = "",
        @SerialName("copilot_plan") val copilotPlan: String = "",
        @SerialName("access_type_sku") val accessTypeSku: String = "",
        @SerialName("token_based_billing") val tokenBasedBilling: Boolean = false,
        @SerialName("quota_reset_date") val quotaResetDate: String? = null,
        @SerialName("quota_reset_date_utc") val quotaResetDateUtc: String? = null,
        @SerialName("quota_snapshots") val quotaSnapshots: JsonElement? = null,
        @SerialName("monthly_quotas") val monthlyQuotas: JsonElement? = null,
        @SerialName("limited_user_quotas") val limitedUserQuotas: JsonElement? = null
    ) {
        val snapshots: Map<String, QuotaSnapshot>
            get() = (quotaSnapshots as? JsonObject).orEmpty()
                .mapNotNull { (key, value) -> QuotaSnapshot.fromJson(value)?.let { key to it } }
                .toMap()
        fun monthlySnapshot(key: String): QuotaSnapshot? {
            val entitlement = numberOf((monthlyQuotas as? JsonObject)?.get(key))?.coerceAtLeast(0.0)
                ?.takeIf { it > 0.0 } ?: return null
            val remaining = numberOf((limitedUserQuotas as? JsonObject)?.get(key))?.coerceAtLeast(0.0)
                ?: return null
            return QuotaSnapshot(
                entitlement = entitlement,
                remaining = remaining,
                percentRemaining = (remaining / entitlement * 100).coerceIn(0.0, 100.0),
                quotaId = key
            )
        }
    }
    @Serializable
    data class QuotaSnapshot(
        val entitlement: Double? = null,
        val remaining: Double? = null,
        @SerialName("percent_remaining") val percentRemaining: Double? = null,
        val unlimited: Boolean = false,
        @SerialName("quota_id") val quotaId: String = ""
    ) {
        val isPlaceholder: Boolean
            get() {
                if (unlimited) return false
                val nothingDecoded = percentRemaining == null &&
                    (entitlement ?: 0.0) == 0.0 && (remaining ?: 0.0) == 0.0
                return nothingDecoded || (entitlement == 0.0 && remaining == 0.0)
            }
        val utilization: Double?
            get() {
                if (unlimited || isPlaceholder) return null
                val percentLeft = percentRemaining
                    ?: entitlement?.takeIf { it > 0 }?.let { total -> remaining?.let { it / total * 100 } }
                    ?: return null
                return ((100.0 - percentLeft) / 100.0).coerceAtLeast(0.0)
            }
        companion object {
            fun fromJson(element: JsonElement?): QuotaSnapshot? {
                val obj = element as? JsonObject ?: return null
                return QuotaSnapshot(
                    entitlement = numberOf(obj["entitlement"]),
                    remaining = numberOf(obj["remaining"]),
                    percentRemaining = numberOf(obj["percent_remaining"]),
                    unlimited = (obj["unlimited"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    quotaId = (obj["quota_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                )
            }
        }
    }
    private fun numberOf(element: JsonElement?): Double? =
        (element as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
    const val KEY_PREMIUM = "premium_interactions"
    const val KEY_CHAT = "chat"
    const val KEY_COMPLETIONS = "completions"
    const val SKU_SUBSCRIPTION_ENDED = "subscription_ended"
    const val CLIENT_ID = "Iv1.b507a08c87ecfe98"
    const val SCOPE = "read:user"
    const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
    const val VERIFICATION_URL = "https://github.com/login/device"
    @Serializable
    data class DeviceCodeResponse(
        @SerialName("device_code") val deviceCode: String = "",
        @SerialName("user_code") val userCode: String = "",
        @SerialName("verification_uri") val verificationUri: String = VERIFICATION_URL,
        @SerialName("expires_in") val expiresIn: Int = 900,
        val interval: Int = 5
    )
    @Serializable
    data class AccessTokenResponse(
        @SerialName("access_token") val accessToken: String? = null,
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null
    )
    const val ERROR_PENDING = "authorization_pending"
    const val ERROR_SLOW_DOWN = "slow_down"
    const val ERROR_DENIED = "access_denied"
    const val ERROR_EXPIRED = "expired_token"
}
