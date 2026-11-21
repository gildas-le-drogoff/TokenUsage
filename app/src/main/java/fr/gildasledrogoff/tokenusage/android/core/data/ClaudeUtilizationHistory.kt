package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.BalanceSample
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionPoint
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository

const val WINDOW_5H = "5-Hour"
const val WINDOW_7D = "7-Day"

const val UTILIZATION_UNIT = "%"

fun claudeUtilizationKey(accountId: String, windowLabel: String): String =
    "$accountId|$windowLabel"

object NoOpBalanceHistoryRepository : BalanceHistoryRepository {
    override suspend fun record(
        service: AiService,
        accountId: String,
        accountLabel: String,
        currency: String,
        totalBalance: Double
    ) = Unit

    override suspend fun latestSample(service: AiService, accountId: String): BalanceSample? = null

    override suspend fun consumption(
        service: AiService,
        accountId: String,
        granularity: ConsumptionGranularity
    ): List<ConsumptionPoint> = emptyList()
}
