package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.AccountBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.CreditBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.DeepseekAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.MultiAccountQuotaRepository
import fr.gildasledrogoff.tokenusage.core.network.deepseek.DeepseekApiService
import fr.gildasledrogoff.tokenusage.core.network.deepseek.DeepseekDto
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.time.Instant
import javax.inject.Inject


class DeepseekRepositoryImpl @Inject constructor(
    private val apiService: DeepseekApiService,
    private val prefsManager: EncryptedPrefsManager,
    private val balanceHistoryRepository: BalanceHistoryRepository
) : MultiAccountQuotaRepository {

    override suspend fun fetchQuota(): Result<QuotaInfo, AppError> {
        val accounts = prefsManager.getDeepseekAccounts()
        if (accounts.isEmpty()) {
            return Result.Failure(AppError.CredentialNotFound(AiService.DEEPSEEK))
        }

        val balances = coroutineScope {
            accounts.map { account ->
                async { fetchAccountBalance(account) }
            }.awaitAll()
        }

        return Result.Success(
            QuotaInfo(
                service = AiService.DEEPSEEK,
                windows = emptyList(),
                extraUsage = null,
                tier = null,
                accountBalances = balances,
                fetchedAt = Instant.now()
            )
        )
    }

    override suspend fun validateCredential(): Result<Unit, AppError> {
        val firstAccount = prefsManager.getDeepseekAccounts().firstOrNull()
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.DEEPSEEK))
        return validateAccount(firstAccount.id)
    }

    override suspend fun validateAccount(accountId: String): Result<Unit, AppError> {
        val account = prefsManager.getDeepseekAccounts().find { it.id == accountId }
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.DEEPSEEK))

        val result = fetchAccountBalance(account)
        result.error?.let { return Result.Failure(it) }
        return Result.Success(Unit)
    }

    private suspend fun fetchAccountBalance(account: DeepseekAccount): AccountBalance {
        return try {
            val response = apiService.getUserBalance(
                authorization = "Bearer ${account.apiKey}"
            )

            if (!response.isSuccessful) {
                return AccountBalance(
                    label = account.label,
                    balance = null,
                    error = handleErrorResponse(response.code())
                )
            }

            val body = response.body()
                ?: return AccountBalance(
                    label = account.label,
                    balance = null,
                    error = AppError.ParseError("Empty balance response")
                )

            val info = body.balanceInfos.firstOrNull()
                ?: return AccountBalance(
                    label = account.label,
                    balance = null,
                    error = AppError.ParseError("No balance info returned")
                )

            val total = info.totalBalance?.toDoubleOrNull()
                ?: return AccountBalance(
                    label = account.label,
                    balance = null,
                    error = AppError.ParseError("Invalid total_balance: ${info.totalBalance}")
                )

            val currency = info.currency ?: ""

            balanceHistoryRepository.record(
                service = AiService.DEEPSEEK,
                accountId = account.id,
                accountLabel = account.label,
                currency = currency,
                totalBalance = total
            )

            AccountBalance(
                label = account.label,
                balance = CreditBalance(
                    currency = currency,
                    totalBalance = total,
                    grantedBalance = info.grantedBalance?.toDoubleOrNull(),
                    toppedUpBalance = info.toppedUpBalance?.toDoubleOrNull(),
                    isAvailable = body.isAvailable ?: true
                )
            )
        } catch (e: IOException) {
            AccountBalance(
                label = account.label,
                balance = null,
                error = AppError.NetworkError(e.message ?: "Network error", e)
            )
        } catch (e: Exception) {
            AccountBalance(
                label = account.label,
                balance = null,
                error = AppError.ParseError("${e::class.simpleName}: ${e.message}", e)
            )
        }
    }

    private fun handleErrorResponse(code: Int): AppError {
        return when (code) {
            401 -> AppError.AuthError(AiService.DEEPSEEK, isTerminal = true)
            429 -> AppError.RateLimited
            503 -> AppError.ServiceUnavailable
            else -> AppError.NetworkError("HTTP $code")
        }
    }
}
