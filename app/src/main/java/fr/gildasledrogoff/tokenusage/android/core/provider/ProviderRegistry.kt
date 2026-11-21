package fr.gildasledrogoff.tokenusage.core.provider

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.accountNaming
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One account to query, with the display identity the UI/widgets use for it:
 * its [label] if the user named it, else a 1-based [number] when the service has several.
 */
data class AccountTarget(
    val provider: QuotaProvider,
    val account: ServiceAccount,
    val label: String?,
    val number: Int?
) {
    val service: AiService get() = provider.service

    suspend fun fetchQuota(): Result<QuotaInfo, AppError> = provider.fetchQuota(account)
}

/** All the providers, bound into a Hilt set by `di/ProviderModule.kt`. */
@Singleton
class ProviderRegistry @Inject constructor(
    providers: Set<@JvmSuppressWildcards QuotaProvider>
) {
    private val byService: Map<AiService, QuotaProvider> = providers.associateBy { it.service }

    init {
        val missing = AiService.entries.filter { it !in byService }
        check(missing.isEmpty()) { "No QuotaProvider registered for $missing" }
    }

    /** Providers in [AiService] declaration order. */
    val providers: List<QuotaProvider> = AiService.entries.map { byService.getValue(it) }

    fun provider(service: AiService): QuotaProvider = byService.getValue(service)

    /** Every configured account of every service, ready to be fetched. */
    fun targets(): List<AccountTarget> = providers.flatMap { targets(it.service) }

    fun targets(service: AiService): List<AccountTarget> {
        val provider = provider(service)
        val accounts = provider.accounts()
        return accounts.mapIndexed { index, account ->
            val (label, number) = accountNaming(account.label, index, accounts.size)
            AccountTarget(provider, account, label, number)
        }
    }

    /** True when at least one account is configured anywhere. */
    fun hasAnyAccount(): Boolean = providers.any { it.accounts().isNotEmpty() }
}
