package fr.gildasledrogoff.tokenusage.core.domain.repository

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import java.time.Instant


data class CachedQuotaResult(
    val service: AiService,
    val accountLabel: String? = null,
    val accountNumber: Int? = null,
    val quota: QuotaInfo?,
    val error: AppError?,
    /** Stable id of the account (appended last so positional callers keep working). */
    val accountId: String? = null
)


data class LastFetchSnapshot(
    val fetchedAt: Instant,

    val results: List<CachedQuotaResult>?
)


interface LastFetchRepository {

    suspend fun snapshot(): LastFetchSnapshot?

    suspend fun record(fetchedAt: Instant, results: List<CachedQuotaResult>)
}
