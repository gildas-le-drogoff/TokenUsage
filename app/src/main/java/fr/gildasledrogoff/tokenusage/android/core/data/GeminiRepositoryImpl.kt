package fr.gildasledrogoff.tokenusage.core.data
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiApiService
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiDto
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiProjectsService
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.provider.BaseQuotaProvider
import fr.gildasledrogoff.tokenusage.core.provider.CredentialField
import fr.gildasledrogoff.tokenusage.core.provider.CredentialForm
import fr.gildasledrogoff.tokenusage.core.provider.httpError
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class GeminiRepositoryImpl @Inject constructor(
    private val apiService: GeminiApiService,
    private val projectsService: GeminiProjectsService,
    private val tokenRefreshService: GeminiTokenRefreshService,
    prefsManager: EncryptedPrefsManager
) : BaseQuotaProvider(AiService.GEMINI, prefsManager) {
    override val form = CredentialForm(
        fields = listOf(
            CredentialField(KEY_ACCESS_TOKEN, R.string.field_access_token, hintRes = R.string.field_secret_replace_hint),
            CredentialField(KEY_REFRESH_TOKEN, R.string.field_refresh_token),
            CredentialField(
                KEY_CLIENT_ID, R.string.field_oauth_client_id,
                hintRes = R.string.field_oauth_client_id_hint, secret = false
            ),
            CredentialField(KEY_CLIENT_SECRET, R.string.field_oauth_client_secret),
            CredentialField(KEY_EXPIRES_AT, R.string.field_token_expiry, secret = false, readOnly = true)
        ),
        assemble = { values, previous ->
            val stored = previous as? Credential.GeminiCredential
            val accessToken = values[KEY_ACCESS_TOKEN].orEmpty().trim().ifBlank { stored?.accessToken.orEmpty() }
            val refreshToken = values[KEY_REFRESH_TOKEN].orEmpty().trim().ifBlank { stored?.refreshToken.orEmpty() }
            val clientId = values[KEY_CLIENT_ID].orEmpty().trim().ifBlank { stored?.oauthClientId.orEmpty() }
            val clientSecret = values[KEY_CLIENT_SECRET].orEmpty().trim().ifBlank { stored?.oauthClientSecret.orEmpty() }
            if (accessToken.isBlank() || refreshToken.isBlank() || clientId.isBlank() || clientSecret.isBlank()) {
                null
            } else {
                Credential.GeminiCredential(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtMs = System.currentTimeMillis() + 3600_000,
                    oauthClientId = clientId,
                    oauthClientSecret = clientSecret,
                    hostedDomain = stored?.hostedDomain?.takeIf { refreshToken == stored.refreshToken }
                )
            }
        },
        prefill = { credential ->
            val gemini = credential as? Credential.GeminiCredential
            mapOf(
                KEY_CLIENT_ID to gemini?.oauthClientId.orEmpty(),
                KEY_EXPIRES_AT to (gemini?.let { formatExpiry(it.expiresAtMs) }.orEmpty())
            )
        }
    )
    override suspend fun fetch(account: ServiceAccount): Result<QuotaInfo, AppError> {
        val credential = account.credential as? Credential.GeminiCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.GEMINI))
        val workingCredential = ensureValidToken(account, credential)
            ?: return Result.Failure(AppError.AuthError(AiService.GEMINI, isTerminal = true))
        return run {
            val result = fetchQuotaWithToken(workingCredential)
            val error = (result as? Result.Failure)?.error
            when {
                error is AppError.AuthError -> {
                    val refreshed = refreshToken(account, workingCredential)
                    if (refreshed != null) {
                        fetchQuotaWithToken(refreshed)
                    } else {
                        Result.Failure(AppError.AuthError(AiService.GEMINI, isTerminal = true))
                    }
                }
                error is AppError.ClientUnsupported && workingCredential.hostedDomain == null ->
                    recheckWithHostedDomain(account, workingCredential) ?: result
                else -> result
            }
        }
    }
    private suspend fun recheckWithHostedDomain(
        account: ServiceAccount,
        credential: Credential.GeminiCredential
    ): Result<QuotaInfo, AppError>? {
        val refreshed = refreshToken(account, credential) ?: return null
        if (refreshed.hostedDomain == null) {
            saveRefreshed(account, refreshed.copy(hostedDomain = ""))
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
    private suspend fun ensureValidToken(
        account: ServiceAccount,
        credential: Credential.GeminiCredential
    ): Credential.GeminiCredential? {
        if (System.currentTimeMillis() < credential.expiresAtMs - 60_000) {
            return credential
        }
        return refreshToken(account, credential)
    }
    private suspend fun refreshToken(
        account: ServiceAccount,
        credential: Credential.GeminiCredential
    ): Credential.GeminiCredential? =
        refreshSerialized(account.id, credential) { exchangeRefreshToken(credential, account) }
    private suspend fun exchangeRefreshToken(
        credential: Credential.GeminiCredential,
        account: ServiceAccount
    ): Credential.GeminiCredential? {
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
                saveRefreshed(account, newCredential)
                newCredential
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
    private fun <T> handleErrorResponse(code: Int): Result<T, AppError> =
        Result.Failure(httpError(AiService.GEMINI, code, authIsTerminal = false))
    private fun formatExpiry(expiresAtMs: Long): String = try {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(expiresAtMs))
    } catch (_: Exception) {
        "—"
    }
    private fun parseInstant(isoString: String): Instant? {
        return try {
            Instant.parse(isoString)
        } catch (_: Exception) {
            null
        }
    }
    companion object {
        const val KEY_ACCESS_TOKEN = "accessToken"
        const val KEY_REFRESH_TOKEN = "refreshToken"
        const val KEY_CLIENT_ID = "oauthClientId"
        const val KEY_CLIENT_SECRET = "oauthClientSecret"
        const val KEY_EXPIRES_AT = "expiresAt"
        private const val FAMILY_PRO = "Pro"
        private const val FAMILY_FLASH = "Flash"
        private const val FAMILY_FLASH_LITE = "Flash Lite"
        private const val TIER_FREE = "free-tier"
        private const val TIER_STANDARD = "standard-tier"
        private const val TIER_LEGACY = "legacy-tier"
        private const val GEMINI_PROJECT_PREFIX = "gen-lang-client"
        private const val GEMINI_PROJECT_LABEL = "generative-language"
    }
}
