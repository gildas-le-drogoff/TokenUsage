package fr.gildasledrogoff.tokenusage.core.data
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.network.codex.CodexApiService
import fr.gildasledrogoff.tokenusage.core.network.codex.CodexDto
import fr.gildasledrogoff.tokenusage.core.network.codex.CodexTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.provider.BaseQuotaProvider
import fr.gildasledrogoff.tokenusage.core.provider.CredentialField
import fr.gildasledrogoff.tokenusage.core.provider.CredentialForm
import fr.gildasledrogoff.tokenusage.core.provider.httpError
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.widget.WidgetPrefsManager
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class CodexRepositoryImpl @Inject constructor(
    private val apiService: CodexApiService,
    private val tokenRefreshService: CodexTokenRefreshService,
    prefsManager: EncryptedPrefsManager,
    private val widgetPrefs: WidgetPrefsManager,
    private val widgetQuotaPublisher: WidgetQuotaPublisher
) : BaseQuotaProvider(AiService.CODEX, prefsManager) {
    override val form = CredentialForm(
        fields = listOf(
            CredentialField(KEY_ACCESS_TOKEN, R.string.field_access_token, hintRes = R.string.field_secret_replace_hint),
            CredentialField(KEY_REFRESH_TOKEN, R.string.field_refresh_token),
            CredentialField(KEY_ACCOUNT_ID, R.string.field_account_id_optional, secret = false)
        ),
        assemble = { values, previous ->
            val stored = previous as? Credential.CodexCredential
            val accessToken = values[KEY_ACCESS_TOKEN].orEmpty().trim().ifBlank { stored?.accessToken.orEmpty() }
            val refreshToken = values[KEY_REFRESH_TOKEN].orEmpty().trim().ifBlank { stored?.refreshToken.orEmpty() }
            if (accessToken.isBlank() || refreshToken.isBlank()) {
                null
            } else {
                Credential.CodexCredential(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    accountId = values[KEY_ACCOUNT_ID].orEmpty().trim().ifBlank { stored?.accountId }
                )
            }
        },
        prefill = { credential ->
            mapOf(KEY_ACCOUNT_ID to ((credential as? Credential.CodexCredential)?.accountId.orEmpty()))
        }
    )
    override suspend fun fetch(account: ServiceAccount): Result<QuotaInfo, AppError> {
        val credential = account.credential as? Credential.CodexCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.CODEX))
        return run {
            val response = apiService.getUsage(
                authorization = "Bearer ${credential.accessToken}",
                accountId = credential.accountId
            )
            when (response.code()) {
                200 -> {
                    val body = response.body()
                        ?: return Result.Failure(AppError.ParseError("Empty response body"))
                    Result.Success(mapToQuotaInfo(body))
                }
                401 -> {
                    val refreshed = refreshToken(account, credential)
                    if (refreshed != null) {
                        val retryResponse = apiService.getUsage(
                            authorization = "Bearer ${refreshed.accessToken}",
                            accountId = refreshed.accountId
                        )
                        if (retryResponse.isSuccessful) {
                            val body = retryResponse.body()
                                ?: return Result.Failure(AppError.ParseError("Empty response body"))
                            Result.Success(mapToQuotaInfo(body))
                        } else {
                            Result.Failure(AppError.AuthError(AiService.CODEX, isTerminal = true))
                        }
                    } else {
                        Result.Failure(AppError.AuthError(AiService.CODEX, isTerminal = true))
                    }
                }
                429 -> Result.Failure(AppError.RateLimited)
                else -> Result.Failure(
                    AppError.NetworkError("HTTP ${response.code()}: ${response.message()}")
                )
            }
        }
    }
    private suspend fun refreshToken(
        account: ServiceAccount,
        credential: Credential.CodexCredential
    ): Credential.CodexCredential? =
        refreshSerialized(account.id, credential) { exchangeRefreshToken(account, credential) }
    private suspend fun exchangeRefreshToken(
        account: ServiceAccount,
        credential: Credential.CodexCredential
    ): Credential.CodexCredential? {
        return try {
            val request = CodexDto.TokenRefreshRequest(refreshToken = credential.refreshToken)
            val response = tokenRefreshService.refreshToken(request)
            if (response.isSuccessful) {
                val body = response.body() ?: return null
                val newCredential = Credential.CodexCredential(
                    accessToken = body.accessToken,
                    refreshToken = body.refreshToken ?: credential.refreshToken,
                    accountId = credential.accountId
                )
                saveRefreshed(account, newCredential)
                newCredential
            } else {
                val errorBody = response.errorBody()?.string() ?: ""
                val isTerminal = response.code() == 401 ||
                    CodexDto.TERMINAL_ERROR_CODES.any { errorBody.contains(it) }
                if (isTerminal) {
                    prefsManager.deleteAccount(AiService.CODEX, account.id)
                    if (!prefsManager.hasCredential(AiService.CODEX)) widgetPrefs.removeServiceEverywhere(AiService.CODEX)
                    widgetQuotaPublisher.refreshWidgets()
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }
    private fun mapToQuotaInfo(response: CodexDto.UsageResponse): QuotaInfo {
        val windows = buildList {
            response.rateLimit?.primaryWindow?.let { window ->
                add(mapRateLimitWindow("primary", window))
            }
            response.rateLimit?.secondaryWindow?.let { window ->
                add(mapRateLimitWindow("secondary", window))
            }
        }
        return QuotaInfo(
            service = AiService.CODEX,
            windows = windows,
            extraUsage = null,
            tier = response.planType?.replaceFirstChar { it.uppercase() },
            fetchedAt = Instant.now()
        )
    }
    private fun mapRateLimitWindow(type: String, window: CodexDto.RateLimitWindow): UsageWindow {
        val label = when (window.limitWindowSeconds) {
            18000L -> "5-Hour"
            604800L -> "7-Day"
            else -> {
                val seconds = window.limitWindowSeconds ?: 0L
                if (seconds > 0) "${seconds / 3600}h" else type.replaceFirstChar { it.uppercase() }
            }
        }
        return UsageWindow(
            label = label,
            utilization = window.usedPercent / 100.0,
            resetsAt = window.resetAt?.let { Instant.ofEpochSecond(it) }
        )
    }
    companion object {
        const val KEY_ACCESS_TOKEN = "accessToken"
        const val KEY_REFRESH_TOKEN = "refreshToken"
        const val KEY_ACCOUNT_ID = "accountId"
        fun parseBalance(element: kotlinx.serialization.json.JsonElement?): Double? {
            if (element == null) return null
            return when (element) {
                is JsonPrimitive -> {
                    element.doubleOrNull ?: element.content.toDoubleOrNull()
                }
                else -> null
            }
        }
    }
}
