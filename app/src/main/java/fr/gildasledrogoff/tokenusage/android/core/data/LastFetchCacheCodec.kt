package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AccountBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.repository.CachedQuotaResult
import fr.gildasledrogoff.tokenusage.core.domain.model.CreditBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.ExtraUsage
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendAmount
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageBreakdownRow
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageSeverity
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object LastFetchCacheCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(results: List<CachedQuotaResult>): String =
        json.encodeToString(results.map { it.toDto() })

    fun decode(payload: String): List<CachedQuotaResult>? = runCatching {
        json.decodeFromString<List<CachedQuotaResultDto>>(payload).mapNotNull { it.toDomain() }
    }.getOrNull()
}


@Serializable
internal data class CachedQuotaResultDto(
    val service: String,
    val accountLabel: String? = null,
    val accountNumber: Int? = null,
    val quota: QuotaInfoDto? = null,
    val error: AppErrorDto? = null
)


@Serializable
internal data class QuotaInfoDto(
    val service: String,
    val windows: List<UsageWindowDto> = emptyList(),
    val extraUsage: ExtraUsageDto? = null,
    val tier: String? = null,
    val accountBalances: List<AccountBalanceDto> = emptyList(),
    val weeklyBreakdown: List<UsageBreakdownRowDto> = emptyList(),
    val spending: List<SpendAmountDto> = emptyList(),
    val fetchedAtEpochMs: Long
)


@Serializable
internal data class UsageWindowDto(
    val label: String,
    val utilization: Double,
    val resetsAtEpochMs: Long? = null,
    val severity: String = UsageSeverity.NORMAL.name
)


@Serializable
internal data class ExtraUsageDto(
    val isEnabled: Boolean,
    val monthlyLimit: Double,
    val usedCredits: Double,
    val utilization: Double,
    val currency: String
)


@Serializable
internal data class AccountBalanceDto(
    val label: String,
    val balance: CreditBalanceDto? = null,
    val error: AppErrorDto? = null
)


@Serializable
internal data class CreditBalanceDto(
    val currency: String,
    val totalBalance: Double,
    val grantedBalance: Double? = null,
    val toppedUpBalance: Double? = null,
    val isAvailable: Boolean
)


@Serializable
internal data class UsageBreakdownRowDto(
    val label: String,
    val percent: Double
)


@Serializable
internal data class SpendAmountDto(
    val period: String,
    val amount: Double,
    val currency: String
)


@Serializable
internal sealed class AppErrorDto {
    @Serializable
    @SerialName("network")
    data class Network(val message: String) : AppErrorDto()

    @Serializable
    @SerialName("auth")
    data class Auth(val service: String, val isTerminal: Boolean, val message: String) : AppErrorDto()

    @Serializable
    @SerialName("rate_limited")
    data object RateLimited : AppErrorDto()

    @Serializable
    @SerialName("parse")
    data class Parse(val message: String) : AppErrorDto()

    @Serializable
    @SerialName("credential_not_found")
    data class CredentialNotFound(val service: String) : AppErrorDto()

    @Serializable
    @SerialName("token_revoked")
    data class TokenRevoked(val service: String) : AppErrorDto()

    @Serializable
    @SerialName("service_unavailable")
    data object ServiceUnavailable : AppErrorDto()

    @Serializable
    @SerialName("client_unsupported")
    data class ClientUnsupported(val service: String) : AppErrorDto()
}


private fun CachedQuotaResult.toDto() = CachedQuotaResultDto(
    service = service.name,
    accountLabel = accountLabel,
    accountNumber = accountNumber,
    quota = quota?.toDto(),
    error = error?.toDto()
)


private fun QuotaInfo.toDto() = QuotaInfoDto(
    service = service.name,
    windows = windows.map { window ->
        UsageWindowDto(
            label = window.label,
            utilization = window.utilization,
            resetsAtEpochMs = window.resetsAt?.toEpochMilli(),
            severity = window.severity.name
        )
    },
    extraUsage = extraUsage?.let { extra ->
        ExtraUsageDto(
            isEnabled = extra.isEnabled,
            monthlyLimit = extra.monthlyLimit,
            usedCredits = extra.usedCredits,
            utilization = extra.utilization,
            currency = extra.currency
        )
    },
    tier = tier,
    accountBalances = accountBalances.map { account ->
        AccountBalanceDto(
            label = account.label,
            balance = account.balance?.let { balance ->
                CreditBalanceDto(
                    currency = balance.currency,
                    totalBalance = balance.totalBalance,
                    grantedBalance = balance.grantedBalance,
                    toppedUpBalance = balance.toppedUpBalance,
                    isAvailable = balance.isAvailable
                )
            },
            error = account.error?.toDto()
        )
    },
    weeklyBreakdown = weeklyBreakdown.map { UsageBreakdownRowDto(it.label, it.percent) },
    spending = spending.map { SpendAmountDto(it.period.name, it.amount, it.currency) },
    fetchedAtEpochMs = fetchedAt.toEpochMilli()
)


private fun AppError.toDto(): AppErrorDto = when (this) {
    is AppError.NetworkError -> AppErrorDto.Network(message)
    is AppError.AuthError -> AppErrorDto.Auth(service.name, isTerminal, message)
    is AppError.RateLimited -> AppErrorDto.RateLimited
    is AppError.ParseError -> AppErrorDto.Parse(message)
    is AppError.CredentialNotFound -> AppErrorDto.CredentialNotFound(service.name)
    is AppError.TokenRevoked -> AppErrorDto.TokenRevoked(service.name)
    is AppError.ServiceUnavailable -> AppErrorDto.ServiceUnavailable
    is AppError.ClientUnsupported -> AppErrorDto.ClientUnsupported(service.name)
}


private fun CachedQuotaResultDto.toDomain(): CachedQuotaResult? {
    val service = aiService(service) ?: return null
    return CachedQuotaResult(
        service = service,
        accountLabel = accountLabel,
        accountNumber = accountNumber,
        quota = quota?.toDomain(service),
        error = error?.toDomain()
    )
}


private fun QuotaInfoDto.toDomain(service: AiService) = QuotaInfo(
    service = service,
    windows = windows.map { window ->
        UsageWindow(
            label = window.label,
            utilization = window.utilization,
            resetsAt = window.resetsAtEpochMs?.let(java.time.Instant::ofEpochMilli),
            severity = enumValue(window.severity, UsageSeverity.NORMAL)
        )
    },
    extraUsage = extraUsage?.let { extra ->
        ExtraUsage(
            isEnabled = extra.isEnabled,
            monthlyLimit = extra.monthlyLimit,
            usedCredits = extra.usedCredits,
            utilization = extra.utilization,
            currency = extra.currency
        )
    },
    tier = tier,
    accountBalances = accountBalances.map { account ->
        AccountBalance(
            label = account.label,
            balance = account.balance?.let { balance ->
                CreditBalance(
                    currency = balance.currency,
                    totalBalance = balance.totalBalance,
                    grantedBalance = balance.grantedBalance,
                    toppedUpBalance = balance.toppedUpBalance,
                    isAvailable = balance.isAvailable
                )
            },
            error = account.error?.toDomain()
        )
    },
    weeklyBreakdown = weeklyBreakdown.map { UsageBreakdownRow(it.label, it.percent) },
    spending = spending.mapNotNull { spend ->
        enumOrNull<SpendPeriod>(spend.period)?.let {
            SpendAmount(it, spend.amount, spend.currency)
        }
    },
    fetchedAt = java.time.Instant.ofEpochMilli(fetchedAtEpochMs)
)


private fun AppErrorDto.toDomain(): AppError = when (this) {
    is AppErrorDto.Network -> AppError.NetworkError(message)
    is AppErrorDto.Auth -> AppError.AuthError(aiService(service) ?: AiService.entries.first(), isTerminal, message)
    is AppErrorDto.RateLimited -> AppError.RateLimited
    is AppErrorDto.Parse -> AppError.ParseError(message)
    is AppErrorDto.CredentialNotFound -> AppError.CredentialNotFound(aiService(service) ?: AiService.entries.first())
    is AppErrorDto.TokenRevoked -> AppError.TokenRevoked(aiService(service) ?: AiService.entries.first())
    is AppErrorDto.ServiceUnavailable -> AppError.ServiceUnavailable
    is AppErrorDto.ClientUnsupported -> AppError.ClientUnsupported(aiService(service) ?: AiService.entries.first())
}


private fun aiService(name: String): AiService? =
    AiService.entries.firstOrNull { it.name == name }


private inline fun <reified T : Enum<T>> enumValue(name: String, default: T): T =
    enumOrNull<T>(name) ?: default


private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }
