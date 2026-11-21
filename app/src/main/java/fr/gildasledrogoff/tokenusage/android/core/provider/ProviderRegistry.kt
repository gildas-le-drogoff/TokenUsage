package fr.gildasledrogoff.tokenusage.core.provider
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.accountNaming
import javax.inject.Inject
import javax.inject.Singleton

data class AccountTarget(
    val provider: QuotaProvider,
    val account: ServiceAccount,
    val label: String?,
    val number: Int?
) {
    val service: AiService get() = provider.service
    suspend fun fetchQuota(): Result<QuotaInfo, AppError> = provider.fetchQuota(account)
}

@Singleton
class ProviderRegistry @Inject constructor(
    providers: Set<@JvmSuppressWildcards QuotaProvider>
) {
    private val byService: Map<AiService, QuotaProvider> = providers.associateBy { it.service }
    init {
        val missing = AiService.entries.filter { it !in byService }
        check(missing.isEmpty()) { "No QuotaProvider registered for $missing" }
    }
    
    val providers: List<QuotaProvider> = AiService.entries.map { byService.getValue(it) }
    fun provider(service: AiService): QuotaProvider = byService.getValue(service)
    
    fun targets(): List<AccountTarget> = providers.flatMap { targets(it.service) }
    fun targets(service: AiService): List<AccountTarget> {
        val provider = provider(service)
        val accounts = provider.accounts()
        return accounts.mapIndexed { index, account ->
            val (label, number) = accountNaming(account.label, index, accounts.size)
            AccountTarget(provider, account, label, number)
        }
    }
    
    fun hasAnyAccount(): Boolean = providers.any { it.accounts().isNotEmpty() }
}
