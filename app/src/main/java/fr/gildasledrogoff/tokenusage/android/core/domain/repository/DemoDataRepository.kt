package fr.gildasledrogoff.tokenusage.core.domain.repository

import fr.gildasledrogoff.tokenusage.core.alert.TimedValue
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.DeepseekAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.status.ProviderStatus
import java.time.Instant

interface DemoDataRepository {
    val isAvailable: Boolean
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
    val supportedServices: Set<AiService>
    suspend fun sampleQuotas(): List<DemoQuota>
    fun sampleDeepseekAccounts(): List<DeepseekAccount>
    val history: BalanceHistoryRepository

    fun sampleUtilizationSeries(
        accountId: String,
        windowLabel: String,
        from: Instant,
        to: Instant
    ): List<TimedValue> = emptyList()

    suspend fun sampleBalanceSeries(
        service: AiService,
        accountId: String,
        since: Instant
    ): List<TimedValue> = emptyList()

    fun sampleProviderStatuses(): Map<AiService, ProviderStatus> = emptyMap()
}

data class DemoQuota(
    val service: AiService,
    val accountLabel: String?,
    val result: Result<QuotaInfo, AppError>,
    val accountNumber: Int? = null,
    val accountId: String? = null,
    val fallback: QuotaInfo? = null
)
