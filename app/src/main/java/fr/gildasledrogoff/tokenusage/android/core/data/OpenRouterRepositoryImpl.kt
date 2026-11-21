package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.AccountBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.CreditBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendAmount
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.network.openrouter.OpenRouterApiService
import fr.gildasledrogoff.tokenusage.core.network.openrouter.OpenRouterDto
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.provider.BaseQuotaProvider
import fr.gildasledrogoff.tokenusage.core.provider.apiKeyForm
import fr.gildasledrogoff.tokenusage.core.provider.httpError
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.R
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class OpenRouterRepositoryImpl @Inject constructor(
    private val apiService: OpenRouterApiService,
    prefsManager: EncryptedPrefsManager
) : BaseQuotaProvider(AiService.OPENROUTER, prefsManager) {

    override val form = apiKeyForm(
        labelRes = R.string.field_api_key,
        fieldHintRes = R.string.field_openrouter_key_hint,
        hintRes = R.string.openrouter_hint
    ) { Credential.ApiKeyCredential(accessToken = it) }

    override suspend fun fetch(account: ServiceAccount): Result<QuotaInfo, AppError> {
        val apiKey = account.credential.accessToken.takeIf { it.isNotBlank() }
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.OPENROUTER))
        return fetchWithKey(apiKey)
    }

    private suspend fun fetchWithKey(apiKey: String): Result<QuotaInfo, AppError> {
        val authorization = "Bearer $apiKey"

        var keyError: AppError? = null
        val key = try {
            val keyResponse = apiService.getKey(authorization)
            if (keyResponse.isSuccessful) {
                keyResponse.body()?.data
                    ?: null.also { keyError = AppError.ParseError("Empty OpenRouter key response") }
            } else {
                keyError = httpError(
                    AiService.OPENROUTER, keyResponse.code(),
                    authCodes = setOf(401, 403), unavailableCodes = setOf(502, 503)
                )
                null
            }
        } catch (e: IOException) {
            keyError = AppError.NetworkError(e.message ?: "Network error", e)
            null
        }

        val credits = runCatching { apiService.getCredits(authorization) }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()?.data
            ?.takeIf { it.totalCredits != null && it.totalUsage != null }

        if (key == null && credits == null) {
            return Result.Failure(keyError ?: AppError.ParseError("Empty OpenRouter key response"))
        }
        return Result.Success(toQuotaInfo(key, credits))
    }

    companion object {
        const val LABEL_KEY_LIMIT = "API key limit"

        
        fun toQuotaInfo(
            key: OpenRouterDto.KeyData?,
            credits: OpenRouterDto.CreditsData?,
            now: Instant = Instant.now()
        ): QuotaInfo {
            val currency = OpenRouterDto.CURRENCY
            val window = key?.let(::keyLimitUtilization)?.let { utilization ->
                UsageWindow(LABEL_KEY_LIMIT, utilization, nextReset(key.limitReset, now))
            }
            val spending = listOfNotNull(
                key?.usageDaily?.let { SpendAmount(SpendPeriod.TODAY, it, currency) },
                key?.usageWeekly?.let { SpendAmount(SpendPeriod.THIS_WEEK, it, currency) },
                key?.usageMonthly?.let { SpendAmount(SpendPeriod.THIS_MONTH, it, currency) }
            )
            val balance = credits?.let {
                val totalCredits = it.totalCredits ?: return@let null
                val totalUsage = it.totalUsage ?: return@let null
                val remaining = (totalCredits - totalUsage).coerceAtLeast(0.0)
                AccountBalance(
                    label = "",
                    balance = CreditBalance(
                        currency = currency,
                        totalBalance = remaining,
                        grantedBalance = null,
                        toppedUpBalance = totalCredits,
                        isAvailable = remaining > 0.0
                    )
                )
            }
            return QuotaInfo(
                service = AiService.OPENROUTER,
                windows = listOfNotNull(window),
                extraUsage = null,
                tier = if (key?.isFreeTier == true) "Free" else null,
                accountBalances = listOfNotNull(balance),
                spending = spending,
                fetchedAt = now
            )
        }

        
        fun keyLimitUtilization(key: OpenRouterDto.KeyData): Double? {
            val limit = key.limit?.takeIf { it > 0.0 } ?: return null
            val used = key.limitRemaining?.let { limit - it.coerceIn(0.0, limit) }
                ?: when (key.limitReset) {
                    OpenRouterDto.RESET_DAILY -> key.usageDaily
                    OpenRouterDto.RESET_WEEKLY -> key.usageWeekly
                    OpenRouterDto.RESET_MONTHLY -> key.usageMonthly
                    else -> null
                }
                ?: key.usage
                ?: return null
            return (used / limit).coerceIn(0.0, 1.0)
        }

        
        fun nextReset(limitReset: String?, now: Instant): Instant? {
            val today = now.atZone(ZoneOffset.UTC).toLocalDate()
            val next = when (limitReset) {
                OpenRouterDto.RESET_DAILY -> today.plusDays(1)
                OpenRouterDto.RESET_WEEKLY -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                OpenRouterDto.RESET_MONTHLY -> today.with(TemporalAdjusters.firstDayOfNextMonth())
                else -> return null
            }
            return next.atStartOfDay(ZoneOffset.UTC).toInstant()
        }
    }
}
