package fr.gildasledrogoff.tokenusage.core.diagnostics

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchSnapshot
import java.time.Duration
import java.time.Instant

data class AccountDiagnostic(
    val accountKey: String,
    val service: AiService,
    val accountLabel: String?,
    val status: DiagnosticStatus,

    val at: Instant?,
    val origin: RefreshOrigin?,

    val error: DiagnosticError?,
    val lastError: DiagnosticError?,
    val lastErrorAt: Instant?
) {

    fun age(now: Instant): Duration? =
        at?.let { Duration.between(it, now).coerceAtLeast(Duration.ZERO) }
}

object DiagnosticsMerger {

    fun merge(
        stored: List<DiagnosticRecord>,
        dashboard: LastFetchSnapshot?,
        isConfigured: (AiService) -> Boolean
    ): List<AccountDiagnostic> {
        val byKey = LinkedHashMap<String, AccountDiagnostic>()
        stored.filter { isConfigured(it.service) }.forEach { r ->
            byKey[r.accountKey] = AccountDiagnostic(
                accountKey = r.accountKey,
                service = r.service,
                accountLabel = r.accountLabel,
                status = r.status,
                at = Instant.ofEpochMilli(r.atEpochMs),
                origin = r.origin,
                error = r.error,
                lastError = r.lastError,
                lastErrorAt = r.lastErrorAtEpochMs?.let(Instant::ofEpochMilli)
            )
        }
        val snapshot = dashboard
        snapshot?.results.orEmpty().filter { isConfigured(it.service) }.forEach { cached ->
            val key = DiagnosticRecord.keyOf(cached.service, cached.accountNumber)
            val error = cached.error?.let(DiagnosticError::from)
            val previous = byKey[key]
            val at = snapshot!!.fetchedAt
            val dashboardIsNewer = previous?.at == null || !at.isBefore(previous.at)
            val mergedLastError: DiagnosticError?
            val mergedLastErrorAt: Instant?
            if (error != null && (previous?.lastErrorAt == null || at.isAfter(previous.lastErrorAt))) {
                mergedLastError = error
                mergedLastErrorAt = at
            } else {
                mergedLastError = previous?.lastError
                mergedLastErrorAt = previous?.lastErrorAt
            }
            byKey[key] = if (previous != null && !dashboardIsNewer) {
                previous.copy(lastError = mergedLastError, lastErrorAt = mergedLastErrorAt)
            } else {
                AccountDiagnostic(
                    accountKey = key,
                    service = cached.service,
                    accountLabel = cached.accountLabel ?: previous?.accountLabel,
                    status = if (error == null) DiagnosticStatus.OK else DiagnosticStatus.ERROR,
                    at = at,
                    origin = RefreshOrigin.DASHBOARD,
                    error = error,
                    lastError = mergedLastError,
                    lastErrorAt = mergedLastErrorAt
                )
            }
        }
        val configured = byKey.values.toMutableList()
        val coveredServices = configured.map { it.service }.toSet()
        AiService.entries.filter { it !in coveredServices }.forEach { service ->
            val state =
                if (isConfigured(service)) DiagnosticStatus.NEVER_REFRESHED else DiagnosticStatus.NOT_CONFIGURED
            configured += AccountDiagnostic(
                accountKey = DiagnosticRecord.keyOf(service, null),
                service = service,
                accountLabel = null,
                status = state,
                at = null,
                origin = null,
                error = null,
                lastError = null,
                lastErrorAt = null
            )
        }
        return configured.sortedWith(compareBy({ it.service.ordinal }, { it.accountKey }))
    }
}
