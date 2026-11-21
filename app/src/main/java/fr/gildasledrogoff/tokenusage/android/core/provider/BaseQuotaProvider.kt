package fr.gildasledrogoff.tokenusage.core.provider

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.workmanager.TokenRefreshLock

/**
 * Plumbing shared by the providers: account lookup, error mapping and stamping the
 * account identity on the result. Subclasses only implement [fetch].
 */
abstract class BaseQuotaProvider(
    final override val service: AiService,
    protected val prefsManager: EncryptedPrefsManager
) : QuotaProvider {

    override fun accounts(): List<ServiceAccount> = prefsManager.getAccounts(service)

    /** Queries the service for [account]; no need to catch I/O or parsing errors. */
    protected abstract suspend fun fetch(account: ServiceAccount): Result<QuotaInfo, AppError>

    final override suspend fun fetchQuota(account: ServiceAccount): Result<QuotaInfo, AppError> =
        guardedFetch { fetch(account) }.map { it.forAccount(account) }

    /** Fetches the first account (single-account shortcut). */
    suspend fun fetchQuota(): Result<QuotaInfo, AppError> {
        val account = accounts().firstOrNull()
            ?: return Result.Failure(AppError.CredentialNotFound(service))
        return fetchQuota(account)
    }

    /** Validates the account with this id. */
    suspend fun validateAccount(accountId: String): Result<Unit, AppError> {
        val account = accounts().find { it.id == accountId }
            ?: return Result.Failure(AppError.CredentialNotFound(service))
        return validate(account)
    }

    /** Validates the first account (single-account shortcut). */
    suspend fun validateCredential(): Result<Unit, AppError> {
        val account = accounts().firstOrNull()
            ?: return Result.Failure(AppError.CredentialNotFound(service))
        return validate(account)
    }

    /** Persists a refreshed credential of [account]. */
    protected fun saveRefreshed(account: ServiceAccount, credential: fr.gildasledrogoff.tokenusage.core.domain.model.Credential) {
        prefsManager.updateAccountCredential(service, account.id, credential)
    }

    /**
     * Runs a refresh-token [exchange] under [TokenRefreshLock], shared with `TokenRefreshWorker`:
     * single-use refresh tokens must never be exchanged twice. If the stored credential changed
     * while waiting for the lock (someone else refreshed it), that one is returned instead.
     */
    protected suspend fun <C : Credential> refreshSerialized(
        accountId: String,
        current: C,
        exchange: suspend () -> C?
    ): C? = TokenRefreshLock.withLock {
        val stored = prefsManager.getAccounts(service).firstOrNull { it.id == accountId }?.credential
        @Suppress("UNCHECKED_CAST")
        if (stored != null && current::class.isInstance(stored) && stored.accessToken != current.accessToken) {
            stored as C
        } else {
            exchange()
        }
    }

    private fun QuotaInfo.forAccount(account: ServiceAccount) = copy(
        accountId = account.id,
        accountLabel = account.label.trim().takeIf { it.isNotEmpty() }
    )
}
