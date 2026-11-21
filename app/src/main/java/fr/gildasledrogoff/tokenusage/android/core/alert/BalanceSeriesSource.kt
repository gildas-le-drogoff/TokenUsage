package fr.gildasledrogoff.tokenusage.core.alert

import fr.gildasledrogoff.tokenusage.core.database.BalanceSnapshotDao
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import java.time.Instant
import javax.inject.Inject

/** Read-only access to the balance measurements already stored by the app. */
interface BalanceSeriesSource {
    suspend fun series(service: AiService, accountId: String, since: Instant): List<TimedValue>
}

class DaoBalanceSeriesSource @Inject constructor(
    private val dao: BalanceSnapshotDao
) : BalanceSeriesSource {
    override suspend fun series(service: AiService, accountId: String, since: Instant): List<TimedValue> =
        dao.snapshotsSince(service.name, accountId, since.toEpochMilli())
            .map { TimedValue(it.totalBalance, Instant.ofEpochMilli(it.timestamp)) }
}
