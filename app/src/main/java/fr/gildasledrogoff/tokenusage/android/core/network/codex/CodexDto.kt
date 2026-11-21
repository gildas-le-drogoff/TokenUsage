package fr.gildasledrogoff.tokenusage.core.network.codex

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement

object CodexDto {
    @Serializable
    data class UsageResponse(
        @SerialName("plan_type") val planType: String? = null,
        @SerialName("rate_limit") val rateLimit: RateLimit? = null,
        val credits: Credits? = null
    )

    @Serializable
    data class RateLimit(
        @SerialName("primary_window")
        @Serializable(with = TolerantWindowSerializer::class)
        val primaryWindow: RateLimitWindow? = null,
        @SerialName("secondary_window")
        @Serializable(with = TolerantWindowSerializer::class)
        val secondaryWindow: RateLimitWindow? = null
    )

    @Serializable
    data class RateLimitWindow(
        @SerialName("used_percent") val usedPercent: Double = 0.0,
        @SerialName("reset_at") val resetAt: Long? = null,
        @SerialName("limit_window_seconds") val limitWindowSeconds: Long? = null
    )

    object TolerantWindowSerializer : KSerializer<RateLimitWindow?> {
        private val delegate = RateLimitWindow.serializer().nullable
        override val descriptor: SerialDescriptor = delegate.descriptor

        override fun deserialize(decoder: Decoder): RateLimitWindow? {
            val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
            val element = jsonDecoder.decodeJsonElement()
            return try {
                jsonDecoder.json.decodeFromJsonElement(delegate, element)
            } catch (_: Exception) {
                null
            }
        }

        override fun serialize(encoder: Encoder, value: RateLimitWindow?) =
            delegate.serialize(encoder, value)
    }

    @Serializable
    data class Credits(
        @SerialName("has_credits") val hasCredits: Boolean = false,
        val unlimited: Boolean = false,
        val balance: JsonElement? = null
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class TokenRefreshRequest(
        @EncodeDefault @SerialName("client_id") val clientId: String = CODEX_CLIENT_ID,
        @EncodeDefault @SerialName("grant_type") val grantType: String = "refresh_token",
        @SerialName("refresh_token") val refreshToken: String,
        @EncodeDefault val scope: String = "openid profile email"
    )

    @Serializable
    data class TokenRefreshResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Int? = null,
        @SerialName("token_type") val tokenType: String? = null
    )

    @Serializable
    data class TokenErrorResponse(
        val error: String? = null,
        @SerialName("error_description") val errorDescription: String? = null
    )

    const val CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    val TERMINAL_ERROR_CODES = setOf(
        "refresh_token_expired",
        "refresh_token_reused",
        "refresh_token_invalidated",
        "invalid_grant"
    )
}
