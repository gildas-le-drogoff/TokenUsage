package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.provider.BaseQuotaProvider
import fr.gildasledrogoff.tokenusage.core.provider.ProviderOption
import fr.gildasledrogoff.tokenusage.core.provider.apiKeyForm
import fr.gildasledrogoff.tokenusage.core.provider.httpError
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.network.zai.ZaiApiService
import fr.gildasledrogoff.tokenusage.core.network.zai.ZaiDto
import fr.gildasledrogoff.tokenusage.core.network.zai.ZaiDto.displayName
import fr.gildasledrogoff.tokenusage.core.network.zai.ZaiDto.isMonthlyTools
import fr.gildasledrogoff.tokenusage.core.network.zai.ZaiDto.planName
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class ZaiRepositoryImpl @Inject constructor(
    private val apiService: ZaiApiService,
    prefsManager: EncryptedPrefsManager
) : BaseQuotaProvider(AiService.ZAI, prefsManager) {

    override val form = apiKeyForm(
        labelRes = R.string.field_api_key,
        fieldHintRes = R.string.field_api_key_zai_hint,
        hintRes = R.string.zai_hint,
        emptyRes = R.string.zai_empty,
        options = listOf(
            ProviderOption(OPTION_HIDE_MONTHLY_TOOLS, R.string.zai_hide_monthly_tools, hintWhenOnRes = R.string.zai_hint_no_tools)
        )
    ) { Credential.ZaiCredential(accessToken = it) }

    override fun optionValue(key: String): Boolean =
        key == OPTION_HIDE_MONTHLY_TOOLS && prefsManager.isZaiMonthlyToolsHidden()

    override fun setOption(key: String, value: Boolean) {
        if (key == OPTION_HIDE_MONTHLY_TOOLS) prefsManager.setZaiMonthlyToolsHidden(value)
    }

    override suspend fun fetch(account: ServiceAccount): Result<QuotaInfo, AppError> {
        val apiKey = account.credential.accessToken
        if (apiKey.isBlank()) {
            return Result.Failure(AppError.CredentialNotFound(AiService.ZAI))
        }
        val response = apiService.getQuotaLimit(
            authorization = "Bearer $apiKey"
        )

        if (!response.isSuccessful) {
            return Result.Failure(httpError(AiService.ZAI, response.code()))
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

    companion object {
        const val OPTION_HIDE_MONTHLY_TOOLS = "hide_monthly_tools"
    }
}
