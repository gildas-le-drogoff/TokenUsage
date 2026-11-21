package fr.gildasledrogoff.tokenusage.core.network.claude

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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
        @Serializable(with = TolerantLimitListSerializer::class)
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
        val percent: Double? = null,
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

    object TolerantLimitListSerializer : KSerializer<List<OAuthLimit>> {
        private val delegate = ListSerializer(OAuthLimit.serializer())
        override val descriptor: SerialDescriptor = delegate.descriptor

        override fun deserialize(decoder: Decoder): List<OAuthLimit> {
            val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return delegate.deserialize(decoder)
            val array = element as? JsonArray ?: return emptyList()
            return array.mapNotNull { parseLimit(it) }
        }

        override fun serialize(encoder: Encoder, value: List<OAuthLimit>) =
            delegate.serialize(encoder, value)

        private fun parseLimit(element: JsonElement): OAuthLimit? {
            val obj = element as? JsonObject ?: return null
            val percentPrimitive = obj["percent"] as? JsonPrimitive
            val percent = percentPrimitive
                ?.takeIf { it !is JsonNull && it.booleanOrNull == null }
                ?.doubleOrNull
                ?.takeIf { it.isFinite() }
            return OAuthLimit(
                kind = obj.string("kind"),
                group = obj.string("group"),
                percent = percent,
                resetsAt = obj.string("resets_at"),
                severity = obj.string("severity"),
                isActive = (obj["is_active"] as? JsonPrimitive)?.booleanOrNull ?: false,
                scope = parseScope(obj["scope"])
            )
        }

        private fun parseScope(element: JsonElement?): OAuthLimitScope? {
            val obj = element as? JsonObject ?: return null
            return OAuthLimitScope(
                model = parseScopeItem(obj["model"]),
                surface = parseScopeItem(obj["surface"])
            )
        }

        private fun parseScopeItem(element: JsonElement?): OAuthLimitScopeItem? {
            val obj = element as? JsonObject ?: return null
            return OAuthLimitScopeItem(displayName = obj.string("display_name"), id = obj.string("id"))
        }

        private fun JsonObject.string(key: String): String? {
            val primitive = this[key] as? JsonPrimitive ?: return null
            return if (primitive.isString) primitive.contentOrNull else null
        }
    }
}
