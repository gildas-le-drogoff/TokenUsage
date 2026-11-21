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

fun List<CachedQuotaResult>.keepingLastKnownQuotas(
    previous: List<CachedQuotaResult>?
): List<CachedQuotaResult> {
    if (previous.isNullOrEmpty()) return this
    val lastKnown = previous
        .filter { it.quota != null && it.accountId != null }
        .associateBy { it.service to it.accountId }
    return map { result ->
        if (result.quota != null || result.accountId == null) return@map result
        val known = lastKnown[result.service to result.accountId]?.quota ?: return@map result
        result.copy(quota = known)
    }
}
