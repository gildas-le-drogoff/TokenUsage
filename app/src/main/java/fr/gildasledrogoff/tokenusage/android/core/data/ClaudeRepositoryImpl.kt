package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.ExtraUsage
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageBreakdownRow
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageSeverity
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeApiService
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeDto
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeWebApiService
import fr.gildasledrogoff.tokenusage.core.provider.BaseQuotaProvider
import fr.gildasledrogoff.tokenusage.core.provider.CredentialField
import fr.gildasledrogoff.tokenusage.core.provider.CredentialForm
import fr.gildasledrogoff.tokenusage.core.provider.LoginAction
import fr.gildasledrogoff.tokenusage.core.provider.LoginMethod
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClaudeRepositoryImpl @Inject constructor(
    private val apiService: ClaudeApiService,
    private val webApiService: ClaudeWebApiService,
    private val tokenRefreshService: ClaudeTokenRefreshService,
    prefsManager: EncryptedPrefsManager,
    private val balanceHistory: BalanceHistoryRepository = NoOpBalanceHistoryRepository
) : BaseQuotaProvider(AiService.CLAUDE, prefsManager) {
    override val form = CredentialForm(
        fields = listOf(
            CredentialField(
                KEY_SESSION_KEY,
                R.string.field_session_key,
                hintRes = R.string.field_session_key_hint
            ),
            CredentialField(KEY_ACCESS_TOKEN, R.string.field_access_token, advanced = true),
            CredentialField(
                KEY_REFRESH_TOKEN, R.string.field_refresh_token,
                hintRes = R.string.field_refresh_token_hint, advanced = true
            )
        ),
        hintRes = R.string.claude_accounts_hint,
        emptyRes = R.string.claude_empty,
        login = LoginAction(
            LoginMethod.WEB_SESSION, R.string.claude_sign_in, KEY_SESSION_KEY,
            hintRes = R.string.claude_sign_in_hint
        ),
        advancedToggleRes = R.string.claude_oauth_toggle,
        assemble = { values, _ ->
            val sessionKey = values[KEY_SESSION_KEY].orEmpty().trim()
            val accessToken = values[KEY_ACCESS_TOKEN].orEmpty().trim()
            when {
                sessionKey.isNotBlank() -> Credential.ClaudeWebCredential(accessToken = sessionKey)
                accessToken.isNotBlank() -> Credential.ClaudeCredential(
                    accessToken = accessToken,
                    refreshToken = values[KEY_REFRESH_TOKEN].orEmpty().trim().ifBlank { null }
                )

                else -> null
            }
        }
    )

    override val supportsPace = true
    override val supportsCurves = true

    override suspend fun fetch(account: ServiceAccount): Result<QuotaInfo, AppError> {
        val result = when (val credential = account.credential) {
            is Credential.ClaudeWebCredential -> fetchQuotaViaWebSession(account.id, credential)
            is Credential.ClaudeCredential -> fetchQuotaViaOAuth(account.id, credential)
            else -> Result.Failure(AppError.CredentialNotFound(AiService.CLAUDE))
        }
        if (result is Result.Success) recordUtilization(account, result.value)
        return result
    }

    private suspend fun recordUtilization(account: ServiceAccount, info: QuotaInfo) {
        for (window in info.windows) {
            if (window.label != WINDOW_5H && window.label != WINDOW_7D) continue
            try {
                balanceHistory.record(
                    service = AiService.CLAUDE,
                    accountId = claudeUtilizationKey(account.id, window.label),
                    accountLabel = account.label,
                    currency = UTILIZATION_UNIT,
                    totalBalance = window.utilization * 100.0
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun fetchQuotaViaWebSession(
        accountId: String,
        credential: Credential.ClaudeWebCredential
    ): Result<QuotaInfo, AppError> {
        if (!credential.isValidSessionKey) {
            return Result.Failure(AppError.AuthError(AiService.CLAUDE, isTerminal = true))
        }
        val cookie = ClaudeWebApiService.sessionCookie(credential.accessToken)
        return try {
            val cachedOrgId = prefsManager.getClaudeOrgId(accountId)
            if (cachedOrgId != null) {
                fetchWebUsage(accountId, cookie, cachedOrgId, allowOrgRetry = true)
            } else {
                val discovery = discoverOrg(cookie)
                val orgId = discovery.orgId ?: return Result.Failure(
                    revokedError(discovery.rawResponse)
                        ?: AppError.AuthError(AiService.CLAUDE, isTerminal = true)
                )
                prefsManager.saveClaudeOrgId(accountId, orgId)
                fetchWebUsage(accountId, cookie, orgId, allowOrgRetry = false)
            }
        } catch (e: IOException) {
            Result.Failure(AppError.NetworkError(e.message ?: "Network error", e))
        } catch (e: Exception) {
            Result.Failure(AppError.ParseError(e.message ?: "Parse error", e))
        }
    }

    private suspend fun discoverOrg(cookie: String): OrgDiscovery {
        val response = webApiService.getOrganizations(cookie)
        val orgId = if (response.isSuccessful) {
            val orgs = response.body()
            val chatOrg = orgs?.firstOrNull { it.hasCapability("chat") }?.orgId
            (chatOrg ?: orgs?.firstOrNull()?.orgId)?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        return OrgDiscovery(orgId, response)
    }

    private data class OrgDiscovery(val orgId: String?, val rawResponse: retrofit2.Response<*>)

    private suspend fun fetchWebUsage(
        accountId: String,
        cookie: String,
        orgId: String,
        allowOrgRetry: Boolean
    ): Result<QuotaInfo, AppError> {
        val response = webApiService.getUsage(orgId, cookie)
        return when (response.code()) {
            200 -> {
                val body = response.body()
                    ?: return Result.Failure(AppError.ParseError("Empty response body"))
                quotaResult(body, tier = null)
            }

            401 -> Result.Failure(AppError.AuthError(AiService.CLAUDE, isTerminal = true))
            403 -> Result.Failure(
                revokedError(response)
                    ?: AppError.NetworkError("HTTP 403: ${response.message()}")
            )

            429 -> Result.Failure(AppError.RateLimited)
            400, 404 -> {
                if (allowOrgRetry) {
                    prefsManager.clearClaudeOrgId(accountId)
                    val discovery = discoverOrg(cookie)
                    val freshOrgId = discovery.orgId ?: return Result.Failure(
                        revokedError(discovery.rawResponse)
                            ?: AppError.AuthError(AiService.CLAUDE, isTerminal = true)
                    )
                    prefsManager.saveClaudeOrgId(accountId, freshOrgId)
                    fetchWebUsage(accountId, cookie, freshOrgId, allowOrgRetry = false)
                } else {
                    Result.Failure(AppError.NetworkError("HTTP ${response.code()}: ${response.message()}"))
                }
            }

            else -> Result.Failure(AppError.NetworkError("HTTP ${response.code()}: ${response.message()}"))
        }
    }

    private suspend fun fetchQuotaViaOAuth(
        accountId: String,
        credential: Credential.ClaudeCredential
    ): Result<QuotaInfo, AppError> {
        val workingCredential = ensureValidToken(accountId, credential)
            ?: return Result.Failure(AppError.AuthError(AiService.CLAUDE, isTerminal = true))
        return try {
            val response = apiService.getUsage("Bearer ${workingCredential.accessToken}")
            when (response.code()) {
                200 -> {
                    val body = response.body()
                        ?: return Result.Failure(AppError.ParseError("Empty response body"))
                    val discoveredTier = extractTierFromHeaders(response)
                        ?: extractTierFromJwt(workingCredential.accessToken)
                    val effectiveTier = discoveredTier ?: workingCredential.rateLimitTier
                    if (discoveredTier != null && discoveredTier != workingCredential.rateLimitTier) {
                        prefsManager.updateClaudeAccountCredential(
                            accountId,
                            workingCredential.copy(rateLimitTier = discoveredTier)
                        )
                    }
                    quotaResult(body, effectiveTier)
                }

                401 -> {
                    val refreshed = refreshToken(accountId, workingCredential)
                    if (refreshed != null) {
                        val retryResponse = apiService.getUsage("Bearer ${refreshed.accessToken}")
                        when {
                            retryResponse.isSuccessful -> {
                                val body = retryResponse.body()
                                    ?: return Result.Failure(AppError.ParseError("Empty response body"))
                                val discoveredTier = extractTierFromHeaders(retryResponse)
                                    ?: extractTierFromJwt(refreshed.accessToken)
                                val effectiveTier = discoveredTier ?: refreshed.rateLimitTier
                                if (discoveredTier != null && discoveredTier != refreshed.rateLimitTier) {
                                    prefsManager.updateClaudeAccountCredential(
                                        accountId,
                                        refreshed.copy(rateLimitTier = discoveredTier)
                                    )
                                }
                                quotaResult(body, effectiveTier)
                            }

                            retryResponse.code() == 403 -> Result.Failure(
                                revokedError(retryResponse)
                                    ?: AppError.AuthError(AiService.CLAUDE, isTerminal = true)
                            )

                            else -> Result.Failure(
                                AppError.AuthError(
                                    AiService.CLAUDE,
                                    isTerminal = true
                                )
                            )
                        }
                    } else {
                        Result.Failure(AppError.AuthError(AiService.CLAUDE, isTerminal = true))
                    }
                }

                429 -> Result.Failure(AppError.RateLimited)
                403 -> Result.Failure(
                    revokedError(response)
                        ?: AppError.NetworkError("HTTP 403: ${response.message()}")
                )

                else -> Result.Failure(
                    AppError.NetworkError("HTTP ${response.code()}: ${response.message()}")
                )
            }
        } catch (e: IOException) {
            Result.Failure(AppError.NetworkError(e.message ?: "Network error", e))
        } catch (e: Exception) {
            Result.Failure(AppError.ParseError(e.message ?: "Parse error", e))
        }
    }

    private fun revokedError(response: retrofit2.Response<*>): AppError? {
        if (response.code() != 403) return null
        val body = runCatching { response.errorBody()?.string().orEmpty() }.getOrDefault("")
        val blockedByMiddleman = body.contains("<html", ignoreCase = true) ||
                body.contains("cloudflare", ignoreCase = true)
        return if (blockedByMiddleman) {
            null
        } else {
            AppError.TokenRevoked(AiService.CLAUDE)
        }
    }

    private suspend fun ensureValidToken(
        accountId: String,
        credential: Credential.ClaudeCredential
    ): Credential.ClaudeCredential? {
        val expiresAt = credential.expiresAt ?: return credential
        if (Instant.now().isBefore(expiresAt.minusSeconds(60))) {
            return credential
        }
        return refreshToken(accountId, credential)
    }

    private suspend fun refreshToken(
        accountId: String,
        credential: Credential.ClaudeCredential
    ): Credential.ClaudeCredential? =
        refreshSerialized(accountId, credential) { exchangeRefreshToken(accountId, credential) }

    private suspend fun exchangeRefreshToken(
        accountId: String,
        credential: Credential.ClaudeCredential
    ): Credential.ClaudeCredential? {
        val refreshToken = credential.refreshToken ?: return null
        return try {
            val response = tokenRefreshService.refreshToken(refreshToken = refreshToken)
            if (response.isSuccessful) {
                val body = response.body() ?: return null
                val newCredential = Credential.ClaudeCredential(
                    accessToken = body.accessToken,
                    refreshToken = body.refreshToken ?: refreshToken,
                    expiresAt = Instant.now().plusSeconds(body.expiresIn.toLong()),
                    scopes = credential.scopes,
                    rateLimitTier = credential.rateLimitTier
                )
                prefsManager.updateClaudeAccountCredential(accountId, newCredential)
                newCredential
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractTierFromHeaders(response: retrofit2.Response<*>): String? {
        val raw = response.headers()["anthropic-ratelimit-tier"]
            ?: response.headers()["x-ratelimit-tier"]
            ?: return null
        return raw.trim().replaceFirstChar { it.uppercase() }
    }

    private fun extractTierFromJwt(accessToken: String): String? {
        return try {
            val parts = accessToken.split(".")
            if (parts.size != 3) return null
            val payload = String(java.util.Base64.getUrlDecoder().decode(parts[1]))
            val json = Json { ignoreUnknownKeys = true }
            val obj = json.parseToJsonElement(payload).jsonObject
            val tier = obj["rate_limit_tier"]?.jsonPrimitive?.content
                ?: obj["rateLimitTier"]?.jsonPrimitive?.content
                ?: return null
            tier.replaceFirstChar { it.uppercase() }
        } catch (_: Exception) {
            null
        }
    }

    private fun quotaResult(
        body: ClaudeDto.OAuthUsageResponse,
        tier: String?
    ): Result<QuotaInfo, AppError> {
        val quotaInfo = mapToQuotaInfo(body, tier)
        if (quotaInfo.windows.isEmpty() && quotaInfo.extraUsage == null) {
            return Result.Failure(
                AppError.ParseError("Usage response matched no known fields — API schema may have changed")
            )
        }
        return Result.Success(quotaInfo)
    }

    private fun mapToQuotaInfo(
        response: ClaudeDto.OAuthUsageResponse,
        tier: String? = null
    ): QuotaInfo {
        val windows = buildList {
            response.fiveHour?.let { mapWindow("5-Hour", it) }?.let(::add)
            response.sevenDay?.let { mapWindow("7-Day", it) }?.let(::add)
            response.sevenDayOauthApps?.let { mapWindow("OAuth Apps", it) }?.let(::add)
            response.sevenDaySonnet?.let { mapWindow("Sonnet", it) }?.let(::add)
            response.sevenDayOpus?.let { mapWindow("Opus", it) }?.let(::add)
            response.sevenDayCowork?.let { mapWindow("Cowork", it) }?.let(::add)
            response.sevenDayOmelette?.let { mapWindow("Claude Design", it) }?.let(::add)
            response.omelettePromotional?.let { mapWindow("Claude Design (Promo)", it) }?.let(::add)
            response.iguanaNecktie?.let { mapWindow("Extended", it) }?.let(::add)
            appendStructuredLimits(this, response.limits)
        }
        val extraUsage = response.extraUsage?.takeIf { it.isEnabled }?.let {
            ExtraUsage(
                isEnabled = it.isEnabled,
                monthlyLimit = it.monthlyLimit / MINOR_UNITS_PER_MAJOR,
                usedCredits = it.usedCredits / MINOR_UNITS_PER_MAJOR,
                utilization = it.utilization,
                currency = it.currency
            )
        }
        val weeklyBreakdown = response.sevenDayBreakdown?.rows
            ?.mapNotNull { row ->
                row.displayName?.takeIf { it.isNotBlank() }
                    ?.let { UsageBreakdownRow(it, row.percent) }
            }
            .orEmpty()
        return QuotaInfo(
            service = AiService.CLAUDE,
            windows = windows,
            extraUsage = extraUsage,
            tier = tier,
            weeklyBreakdown = weeklyBreakdown,
            fetchedAt = Instant.now()
        )
    }

    private fun appendStructuredLimits(
        builder: MutableList<UsageWindow>,
        limits: List<ClaudeDto.OAuthLimit>
    ) {
        for (limit in limits) {
            val percent = limit.percent ?: continue
            val label = limitLabel(limit) ?: continue
            if (builder.any { it.label == label }) continue
            builder.add(
                UsageWindow(
                    label = label,
                    utilization = (percent / 100.0).coerceIn(0.0, 1.0),
                    resetsAt = limit.resetsAt?.let { parseInstant(it) },
                    severity = mapSeverity(limit.severity),
                    windowMinutes = when (limit.kind) {
                        "session" -> SESSION_WINDOW_MINUTES
                        "weekly_all", "weekly_scoped" -> WEEKLY_WINDOW_MINUTES
                        else -> null
                    }
                )
            )
        }
    }

    private fun limitLabel(limit: ClaudeDto.OAuthLimit): String? {
        limit.scope?.model?.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        limit.scope?.surface?.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return when (limit.kind) {
            "session" -> "5-Hour"
            "weekly_all" -> "7-Day"
            "weekly_scoped" -> "Weekly scoped"
            null -> null
            else -> humanizeUsageKey(limit.kind)
        }
    }

    private fun humanizeUsageKey(key: String): String =
        key.split("_").joinToString(" ") { part ->
            part.replaceFirstChar { it.uppercase() }
        }

    private fun mapSeverity(severity: String?): UsageSeverity = when (severity) {
        "warning" -> UsageSeverity.WARNING
        "critical" -> UsageSeverity.CRITICAL
        else -> UsageSeverity.NORMAL
    }

    private fun mapWindow(label: String, window: ClaudeDto.OAuthUsageWindow): UsageWindow? {
        val utilization = window.utilization ?: return null
        return UsageWindow(
            label = label,
            utilization = (utilization / 100.0).coerceIn(0.0, 1.0),
            resetsAt = window.resetsAt?.let { parseInstant(it) },
            severity = if (window.lockedReason != null) UsageSeverity.CRITICAL else UsageSeverity.NORMAL,
            windowMinutes = when (label) {
                "5-Hour" -> SESSION_WINDOW_MINUTES
                "Extended", "Claude Design (Promo)" -> null
                else -> WEEKLY_WINDOW_MINUTES
            }
        )
    }

    private fun parseInstant(isoString: String): Instant? {
        val offsetParsed = runCatching { OffsetDateTime.parse(isoString).toInstant() }.getOrNull()
        if (offsetParsed != null) return offsetParsed
        return runCatching { Instant.parse(isoString) }.getOrNull()
    }

    companion object {
        private const val MINOR_UNITS_PER_MAJOR = 100.0
        private const val SESSION_WINDOW_MINUTES = 300
        private const val WEEKLY_WINDOW_MINUTES = 10080
        const val KEY_SESSION_KEY = "sessionKey"
        const val KEY_ACCESS_TOKEN = "accessToken"
        const val KEY_REFRESH_TOKEN = "refreshToken"
    }
}
