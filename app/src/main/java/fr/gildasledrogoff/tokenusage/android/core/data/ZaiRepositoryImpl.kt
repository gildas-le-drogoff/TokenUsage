package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.domain.model.ZaiAccount
import fr.gildasledrogoff.tokenusage.core.domain.repository.ZaiRepository
import fr.gildasledrogoff.tokenusage.core.network.zai.ZaiApiService
import fr.gildasledrogoff.tokenusage.core.network.zai.ZaiDto
import fr.gildasledrogoff.tokenusage.core.network.zai.ZaiDto.displayName
import fr.gildasledrogoff.tokenusage.core.network.zai.ZaiDto.isMonthlyTools
import fr.gildasledrogoff.tokenusage.core.network.zai.ZaiDto.planName
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Instant
import javax.inject.Inject


class ZaiRepositoryImpl @Inject constructor(
    private val apiService: ZaiApiService,
    private val prefsManager: EncryptedPrefsManager
) : ZaiRepository {

    
    override suspend fun fetchQuota(): Result<QuotaInfo, AppError> {
        val account = prefsManager.getZaiAccounts().firstOrNull()
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.ZAI))
        return fetchQuotaForAccount(account)
    }

    override suspend fun fetchQuotaForAccount(account: ZaiAccount): Result<QuotaInfo, AppError> {
        if (account.apiKey.isBlank()) {
            return Result.Failure(AppError.CredentialNotFound(AiService.ZAI))
        }
        return try {
            fetchQuotaWithKey(account)
        } catch (e: IOException) {
            Result.Failure(AppError.NetworkError(e.message ?: "Network error", e))
        } catch (e: Exception) {
            Result.Failure(AppError.ParseError("${e::class.simpleName}: ${e.message}", e))
        }
    }

    override suspend fun validateCredential(): Result<Unit, AppError> {
        val firstAccount = prefsManager.getZaiAccounts().firstOrNull()
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.ZAI))
        return validateAccount(firstAccount.id)
    }

    override suspend fun validateAccount(accountId: String): Result<Unit, AppError> {
        val account = prefsManager.getZaiAccounts().find { it.id == accountId }
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.ZAI))
        return when (val result = fetchQuotaForAccount(account)) {
            is Result.Success -> Result.Success(Unit)
            is Result.Failure -> Result.Failure(result.error)
        }
    }

    private suspend fun fetchQuotaWithKey(account: ZaiAccount): Result<QuotaInfo, AppError> {
        val response = apiService.getQuotaLimit(
            authorization = "Bearer ${account.apiKey}"
        )

        if (!response.isSuccessful) {
            return handleErrorResponse(response.code())
        }

        val body = response.body()
            ?: return Result.Failure(AppError.ParseError("Empty Z.ai quota response"))

        if (body.code == ZaiDto.ERROR_CODE_INVALID_TOKEN) {
            return Result.Failure(AppError.AuthError(AiService.ZAI, isTerminal = true))
        }
        if (!body.success || body.data == null) {
            val message = body.msg.ifBlank { "Z.ai API returned an unsuccessful response" }
            return Result.Failure(AppError.ParseError(message))
        }

        val limits = body.data.limits
        if (limits.isEmpty()) {
            return Result.Failure(AppError.ParseError("Z.ai quota response contains no limits"))
        }

        val hideMonthlyTools = prefsManager.isZaiMonthlyToolsHidden()
        val windows = limits.filterNot { hideMonthlyTools && it.isMonthlyTools }.map { limit ->
            UsageWindow(
                label = limit.displayName,
                utilization = (limit.percentage / 100.0).coerceIn(0.0, 1.0),
                resetsAt = limit.nextResetTime.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
            )
        }

        return Result.Success(
            QuotaInfo(
                service = AiService.ZAI,
                windows = windows,
                extraUsage = null,
                tier = planName(body.data.level),
                fetchedAt = Instant.now()
            )
        )
    }

    private fun <T> handleErrorResponse(code: Int): Result<T, AppError> {
        return when (code) {
            401 -> Result.Failure(AppError.AuthError(AiService.ZAI, isTerminal = true))
            429 -> Result.Failure(AppError.RateLimited)
            503 -> Result.Failure(AppError.ServiceUnavailable)
            else -> Result.Failure(AppError.NetworkError("HTTP $code"))
        }
    }
}
