package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.domain.repository.QuotaRepository
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiApiService
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiDto
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiProjectsService
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

class GeminiRepositoryImpl @Inject constructor(
    private val apiService: GeminiApiService,
    private val projectsService: GeminiProjectsService,
    private val tokenRefreshService: GeminiTokenRefreshService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.GEMINI)
            as? Credential.GeminiCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.GEMINI))

        val workingCredential = ensureValidToken(credential)
            ?: return Result.Failure(AppError.AuthError(AiService.GEMINI, isTerminal = true))

        return try {
            val result = fetchQuotaWithToken(workingCredential)
            val error = (result as? Result.Failure)?.error
            when {
                error is AppError.AuthError -> {
                    val refreshed = refreshToken(workingCredential)
                    if (refreshed != null) {
                        fetchQuotaWithToken(refreshed)
                    } else {
                        Result.Failure(AppError.AuthError(AiService.GEMINI, isTerminal = true))
                    }
                }
                error is AppError.ClientUnsupported && workingCredential.hostedDomain == null ->
                    recheckWithHostedDomain(workingCredential) ?: result
                else -> result
            }
        } catch (e: IOException) {
            Result.Failure(AppError.NetworkError(e.message ?: "Network error", e))
        } catch (e: Exception) {
            Result.Failure(AppError.ParseError("${e::class.simpleName}: ${e.message}", e))
        }
    }

    
    private suspend fun recheckWithHostedDomain(
        credential: Credential.GeminiCredential
    ): Result<QuotaInfo, AppError>? {
        val refreshed = refreshToken(credential) ?: return null
        if (refreshed.hostedDomain == null) {
            prefsManager.saveCredential(AiService.GEMINI, refreshed.copy(hostedDomain = ""))
            return null
        }
        if (refreshed.hostedDomain.isEmpty()) return null
        return fetchQuotaWithToken(refreshed)
    }

    private suspend fun fetchQuotaWithToken(
        credential: Credential.GeminiCredential
    ): Result<QuotaInfo, AppError> {
        val authorization = "Bearer ${credential.accessToken}"
        val hostedDomain = credential.hostedDomain?.takeIf { it.isNotEmpty() }

        val status = when (val loaded = loadCodeAssistStatus(authorization, hostedDomain)) {
            is Result.Success -> loaded.value
            is Result.Failure -> return Result.Failure(loaded.error)
        }

        val projectId = status.projectId ?: discoverProjectId(authorization)

        val quotaResponse = apiService.retrieveUserQuota(
            authorization = authorization,
            body = GeminiDto.RetrieveUserQuotaRequest(project = projectId)
        )

        if (!quotaResponse.isSuccessful) {
            val errorBody = quotaResponse.errorBody()?.string().orEmpty()
            
            val shutdown = GeminiDto.isConsumerTierDeprecationSignal(errorBody) ||
                (quotaResponse.code() == 403 && status.consumerClientUnsupported && status.tierId != TIER_STANDARD)
            return if (shutdown) {
                Result.Failure(AppError.ClientUnsupported(AiService.GEMINI))
            } else {
                handleErrorResponse(quotaResponse.code())
            }
        }

        val quotaBody = quotaResponse.body()
            ?: return Result.Failure(AppError.ParseError("Empty quota response"))
        if (quotaBody.buckets.isEmpty()) {
            return Result.Failure(AppError.ParseError("No quota buckets in response"))
        }

        return Result.Success(mapToQuotaInfo(quotaBody, resolvePlan(status, hostedDomain)))
    }

    override suspend fun validateCredential(): Result<Unit, AppError> {
        return when (val result = fetchQuota()) {
            is Result.Success -> Result.Success(Unit)
            is Result.Failure -> Result.Failure(result.error)
        }
    }

    
    private data class CodeAssistStatus(
        val tierId: String?,
        val projectId: String?,
        val paidTierName: String?,
        val consumerClientUnsupported: Boolean
    ) {
        companion object {
            val EMPTY = CodeAssistStatus(null, null, null, false)
        }
    }

    
    private suspend fun loadCodeAssistStatus(
        authorization: String,
        hostedDomain: String?
    ): Result<CodeAssistStatus, AppError> {
        val response = try {
            apiService.loadCodeAssist(authorization = authorization, body = GeminiDto.LoadCodeAssistRequest())
        } catch (_: IOException) {
            return Result.Success(CodeAssistStatus.EMPTY)
        }

        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string().orEmpty()
            return when {
                GeminiDto.isConsumerTierDeprecationSignal(errorBody) ->
                    Result.Failure(AppError.ClientUnsupported(AiService.GEMINI))
                response.code() == 401 || response.code() == 429 -> handleErrorResponse(response.code())
                else -> Result.Success(CodeAssistStatus.EMPTY)
            }
        }
        val body = response.body() ?: return Result.Success(CodeAssistStatus.EMPTY)

        val tierId = body.currentTier?.id
        val paidTierName = body.paidTier?.name?.trim()?.takeIf { it.isNotEmpty() }
        val consumerClientUnsupported = paidTierName == null && hostedDomain == null &&
            body.ineligibleTiers.any { tier ->
                listOfNotNull(tier.reasonCode, tier.reasonMessage).any(GeminiDto::isConsumerTierDeprecationSignal)
            }
        if (tierId == null && consumerClientUnsupported) {
            return Result.Failure(AppError.ClientUnsupported(AiService.GEMINI))
        }
        return Result.Success(
            CodeAssistStatus(
                tierId = tierId,
                projectId = extractProjectId(body),
                paidTierName = paidTierName,
                consumerClientUnsupported = consumerClientUnsupported
            )
        )
    }

    private fun extractProjectId(response: GeminiDto.LoadCodeAssistResponse): String? {
        val raw = when (val element = response.cloudaicompanionProject) {
            is JsonPrimitive -> element.takeIf { it.isString }?.content
            is JsonObject -> listOf("id", "projectId").firstNotNullOfOrNull { key ->
                (element[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
            else -> null
        }
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    
    private suspend fun discoverProjectId(authorization: String): String? {
        val projects = try {
            projectsService.listProjects(authorization).takeIf { it.isSuccessful }?.body()?.projects
        } catch (_: Exception) {
            null
        } ?: return null
        return projects.firstNotNullOfOrNull { project ->
            project.projectId?.takeIf {
                it.startsWith(GEMINI_PROJECT_PREFIX) || GEMINI_PROJECT_LABEL in project.labels
            }
        }
    }

    
    private fun resolvePlan(status: CodeAssistStatus, hostedDomain: String?): String? {
        status.paidTierName?.let { return it }
        return when (status.tierId) {
            TIER_STANDARD -> "Paid"
            TIER_FREE -> if (hostedDomain != null) "Workspace" else "Free"
            TIER_LEGACY -> "Legacy"
            else -> status.tierId
        }
    }

    private fun mapToQuotaInfo(response: GeminiDto.QuotaResponse, tier: String?): QuotaInfo {
        fun familyOf(modelId: String): String? {
            val id = modelId.lowercase()
            return when {
                "flash-lite" in id -> FAMILY_FLASH_LITE
                "flash" in id -> FAMILY_FLASH
                "pro" in id -> FAMILY_PRO
                else -> null
            }
        }

        val usable = response.buckets.mapNotNull { bucket ->
            val family = bucket.modelId?.let(::familyOf) ?: return@mapNotNull null
            val remaining = bucket.remainingFraction ?: return@mapNotNull null
            Triple(family, remaining, bucket.resetTime)
        }

        val windows = listOf(FAMILY_PRO, FAMILY_FLASH, FAMILY_FLASH_LITE).mapNotNull { family ->
            val (_, remaining, resetTime) = usable
                .filter { it.first == family }
                .minByOrNull { it.second }
                ?: return@mapNotNull null
            UsageWindow(
                label = family,
                utilization = 1.0 - remaining,
                resetsAt = resetTime?.let { ts -> parseInstant(ts) }
            )
        }

        return QuotaInfo(
            service = AiService.GEMINI,
            windows = windows,
            extraUsage = null,
            tier = tier,
            fetchedAt = Instant.now()
        )
    }

    private suspend fun ensureValidToken(credential: Credential.GeminiCredential): Credential.GeminiCredential? {
        if (System.currentTimeMillis() < credential.expiresAtMs - 60_000) {
            return credential
        }
        return refreshToken(credential)
    }

    private suspend fun refreshToken(credential: Credential.GeminiCredential): Credential.GeminiCredential? {
        return try {
            val response = tokenRefreshService.refreshToken(
                refreshToken = credential.refreshToken,
                clientId = credential.oauthClientId,
                clientSecret = credential.oauthClientSecret
            )
            if (response.isSuccessful) {
                val body = response.body() ?: return null
                val expiresIn = body.expiresIn ?: 3600
                val newCredential = Credential.GeminiCredential(
                    accessToken = body.accessToken,
                    refreshToken = body.refreshToken ?: credential.refreshToken,
                    expiresAtMs = System.currentTimeMillis() + (expiresIn * 1000L),
                    oauthClientId = credential.oauthClientId,
                    oauthClientSecret = credential.oauthClientSecret,
                    hostedDomain = GeminiDto.refreshedHostedDomain(body.idToken, credential.hostedDomain)
                )
                prefsManager.saveCredential(AiService.GEMINI, newCredential)
                newCredential
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun <T> handleErrorResponse(code: Int): Result<T, AppError> {
        return when (code) {
            401 -> Result.Failure(AppError.AuthError(AiService.GEMINI, isTerminal = false))
            429 -> Result.Failure(AppError.RateLimited)
            503 -> Result.Failure(AppError.ServiceUnavailable)
            else -> Result.Failure(AppError.NetworkError("HTTP $code"))
        }
    }

    private fun parseInstant(isoString: String): Instant? {
        return try {
            Instant.parse(isoString)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val FAMILY_PRO = "Pro"
        const val FAMILY_FLASH = "Flash"
        const val FAMILY_FLASH_LITE = "Flash Lite"

        const val TIER_FREE = "free-tier"
        const val TIER_STANDARD = "standard-tier"
        const val TIER_LEGACY = "legacy-tier"

        const val GEMINI_PROJECT_PREFIX = "gen-lang-client"
        const val GEMINI_PROJECT_LABEL = "generative-language"
    }
}
