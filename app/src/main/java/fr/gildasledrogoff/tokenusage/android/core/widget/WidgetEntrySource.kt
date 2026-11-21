package fr.gildasledrogoff.tokenusage.core.widget

import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.isConnectivityFailure

internal sealed interface WidgetEntrySource {
    data class Quota(val quota: QuotaInfo, val status: WidgetDataStatus) : WidgetEntrySource

    data class Previous(val account: CachedAccount) : WidgetEntrySource

    data class Placeholder(val status: WidgetDataStatus) : WidgetEntrySource
}


internal fun WidgetAccountQuota.resolveSource(
    previous: List<CachedAccount>,
    accountCount: Int,
    offline: Boolean
): WidgetEntrySource {
    quota?.let { return WidgetEntrySource.Quota(it, WidgetDataStatus.FRESH) }
    val status = if (offline || error?.isConnectivityFailure == true) {
        WidgetDataStatus.OFFLINE
    } else {
        WidgetDataStatus.FAILED
    }
    val cached = previous.findPrevious(this, accountCount)
    val known = fallback
    if (known != null) {
        val cachedAt = cached?.dataFetchedAtEpochMillis
        if (cachedAt == null || known.fetchedAt.toEpochMilli() >= cachedAt) {
            return WidgetEntrySource.Quota(known, status)
        }
    }
    if (cached != null) return WidgetEntrySource.Previous(cached.copy(status = status))
    return WidgetEntrySource.Placeholder(status)
}

private fun List<CachedAccount>.findPrevious(
    entry: WidgetAccountQuota,
    accountCount: Int
): CachedAccount? {
    firstOrNull { it.accountLabel == entry.accountLabel && it.accountNumber == entry.accountNumber }
        ?.let { return it }
    return singleOrNull()?.takeIf { accountCount == 1 }
        ?.copy(accountLabel = entry.accountLabel, accountNumber = entry.accountNumber)
}
