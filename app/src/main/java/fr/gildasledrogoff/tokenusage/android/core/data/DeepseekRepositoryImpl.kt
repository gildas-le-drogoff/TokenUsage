package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.AccountBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.CreditBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.network.deepseek.DeepseekApiService
import fr.gildasledrogoff.tokenusage.core.network.deepseek.DeepseekDto
import fr.gildasledrogoff.tokenusage.core.provider.BaseQuotaProvider
import fr.gildasledrogoff.tokenusage.core.provider.apiKeyForm
import fr.gildasledrogoff.tokenusage.core.provider.httpError
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.R
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class DeepseekRepositoryImpl @Inject constructor(
    private val apiService: DeepseekApiService,
    prefsManager: EncryptedPrefsManager,
    private val balanceHistoryRepository: BalanceHistoryRepository
) : BaseQuotaProvider(AiService.DEEPSEEK, prefsManager) {

    override val form = apiKeyForm(
        labelRes = R.string.field_api_key,
        fieldHintRes = R.string.field_api_key_format,
        hintRes = R.string.deepseek_hint,
        emptyRes = R.string.deepseek_empty
    ) { Credential.DeepseekCredential(accessToken = it) }

    override val tracksBalanceHistory = true

    override suspend fun fetch(account: ServiceAccount): Result<QuotaInfo, AppError> {
        val balance = fetchAccountBalance(account)
        balance.error?.let { return Result.Failure(it) }
        return Result.Success(
            QuotaInfo(
                service = AiService.DEEPSEEK,
                windows = emptyList(),
                extraUsage = null,
                tier = null,
                accountBalances = listOf(balance),
                fetchedAt = Instant.now()
            )
        )
    }

    private suspend fun fetchAccountBalance(account: ServiceAccount): AccountBalance {
        return try {
            val response = apiService.getUserBalance(
                authorization = "Bearer ${account.credential.accessToken}"
            )

            if (!response.isSuccessful) {
                return AccountBalance(
                    label = account.label,
                    balance = null,
                    error = httpError(AiService.DEEPSEEK, response.code())
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
}
