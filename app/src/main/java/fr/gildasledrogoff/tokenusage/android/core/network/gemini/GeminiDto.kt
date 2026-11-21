package fr.gildasledrogoff.tokenusage.core.network.gemini

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.Base64

object GeminiDto {
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class LoadCodeAssistRequest(
        @EncodeDefault val metadata: Metadata = Metadata()
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class Metadata(
        @EncodeDefault val ideType: String = "GEMINI_CLI",
        @EncodeDefault val pluginType: String = "GEMINI"
    )

    @Serializable
    data class LoadCodeAssistResponse(
        val cloudaicompanionProject: JsonElement? = null,
        val currentTier: Tier? = null,
        val paidTier: Tier? = null,
        val ineligibleTiers: List<IneligibleTier> = emptyList()
    )

    @Serializable
    data class IneligibleTier(
        val reasonCode: String? = null,
        val reasonMessage: String? = null
    )

    @Serializable
    data class Tier(
        val id: String? = null,
        val name: String? = null
    )

    @Serializable
    data class RetrieveUserQuotaRequest(
        val project: String? = null
    )

    @Serializable
    data class QuotaResponse(
        val buckets: List<QuotaBucket> = emptyList()
    )

    @Serializable
    data class QuotaBucket(
        val modelId: String? = null,
        val remainingFraction: Double? = null,
        val resetTime: String? = null,
        val tokenType: String? = null
    )

    @Serializable
    data class TokenRefreshResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Int? = null,
        @SerialName("id_token") val idToken: String? = null
    )

    @Serializable
    data class ProjectsResponse(
        val projects: List<Project> = emptyList()
    )

    @Serializable
    data class Project(
        val projectId: String? = null,
        val labels: Map<String, String> = emptyMap()
    )

    @Serializable
    private data class IdTokenClaims(val hd: String? = null)

    private val claimsJson =
        Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun hostedDomainOf(idToken: String?): String? {
        val payload = idToken?.split('.')?.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val decoded =
                Base64.getUrlDecoder().decode(payload.trimEnd('=')).toString(Charsets.UTF_8)
            claimsJson.decodeFromString<IdTokenClaims>(decoded).hd?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    fun refreshedHostedDomain(idToken: String?, previous: String?): String? =
        if (idToken.isNullOrBlank()) previous else hostedDomainOf(idToken).orEmpty()

    fun isConsumerTierDeprecationSignal(text: String): Boolean {
        val normalized = text.lowercase()
        return "unsupported_client" in normalized ||
                "ineligibletiererror" in normalized ||
                ("no longer supported" in normalized && "gemini code assist" in normalized) ||
                ("migrate" in normalized && "antigravity" in normalized && "gemini" in normalized)
    }
}
