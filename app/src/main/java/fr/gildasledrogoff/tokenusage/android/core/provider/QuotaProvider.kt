package fr.gildasledrogoff.tokenusage.core.provider

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount

/**
 * One AI service. Everything that used to be a `when (service)` somewhere in the app
 * (which credential to ask for, how to query a quota, how to display it) lives here.
 *
 * Adding a service = an [AiService] entry + an implementation of this interface registered
 * with `@Binds @IntoSet` in `di/ProviderModule.kt`.
 */
interface QuotaProvider {

    val service: AiService

    /** What the account editor must ask the user for. */
    val form: CredentialForm

    /** Accounts currently configured for this service (empty = service not in use). */
    fun accounts(): List<ServiceAccount>

    /**
     * Quota of one account. The result carries the account identity
     * ([QuotaInfo.accountId] / [QuotaInfo.accountLabel]).
     */
    suspend fun fetchQuota(account: ServiceAccount): Result<QuotaInfo, AppError>

    /** True when the balances of this service are recorded, so the dashboard can chart their consumption. */
    val tracksBalanceHistory: Boolean get() = false

    /** Value of a service-wide [ProviderOption] declared in [form]. */
    fun optionValue(key: String): Boolean = false

    fun setOption(key: String, value: Boolean) = Unit

    /** Checks that [account]'s credential works. */
    suspend fun validate(account: ServiceAccount): Result<Unit, AppError> =
        fetchQuota(account).map { }
}
