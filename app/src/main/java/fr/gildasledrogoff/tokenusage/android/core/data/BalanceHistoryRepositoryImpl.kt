package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.database.BalanceSnapshotDao
import fr.gildasledrogoff.tokenusage.core.database.BalanceSnapshotEntity
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.BalanceSample
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionPoint
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class BalanceHistoryRepositoryImpl @Inject constructor(
    private val dao: BalanceSnapshotDao,
    private val clock: Clock
) : BalanceHistoryRepository {

    override suspend fun record(
        service: AiService,
        accountId: String,
        accountLabel: String,
        currency: String,
        totalBalance: Double
    ) {
        val now = clock.millis()
        dao.insert(
            BalanceSnapshotEntity(
                service = service.name,
                accountId = accountId,
                accountLabel = accountLabel,
                currency = currency,
                totalBalance = totalBalance,
                timestamp = now
            )
        )
        dao.deleteOlderThan(now - RETENTION_DAYS * 24L * 60 * 60 * 1000)
    }

    override suspend fun latestSample(service: AiService, accountId: String): BalanceSample? {
        return dao.latest(service.name, accountId)?.toSample()
    }

    override suspend fun consumption(
        service: AiService,
        accountId: String,
        granularity: ConsumptionGranularity
    ): List<ConsumptionPoint> {
        val from = clock.millis() - granularity.sampleWindowDays * 24L * 60 * 60 * 1000
        val samples = dao.snapshotsSince(service.name, accountId, from).map { it.toSample() }
        val aggregatorSamples = samples.map {
            BalanceConsumptionAggregator.Sample(it.totalBalance, it.currency, it.timestamp)
        }
        return when (granularity) {
            ConsumptionGranularity.DAILY ->
                BalanceConsumptionAggregator.daily(aggregatorSamples)
            ConsumptionGranularity.MONTHLY ->
                BalanceConsumptionAggregator.monthly(aggregatorSamples)
        }
    }

    private fun BalanceSnapshotEntity.toSample() = BalanceSample(
        accountId = accountId,
        accountLabel = accountLabel,
        currency = currency,
        totalBalance = totalBalance,
        timestamp = Instant.ofEpochMilli(timestamp)
    )

    companion object {
        
        const val RETENTION_DAYS = 400
    }
}
