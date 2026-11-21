package fr.gildasledrogoff.tokenusage.core.domain.repository
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.BalanceSample
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionPoint
interface BalanceHistoryRepository {
    suspend fun record(
        service: AiService,
        accountId: String,
        accountLabel: String,
        currency: String,
        totalBalance: Double
    )
    suspend fun latestSample(service: AiService, accountId: String): BalanceSample?
    suspend fun consumption(
        service: AiService,
        accountId: String,
        granularity: ConsumptionGranularity
    ): List<ConsumptionPoint>
}
