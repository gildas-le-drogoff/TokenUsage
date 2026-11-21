package fr.gildasledrogoff.tokenusage.core.provider

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.workmanager.TokenRefreshLock

abstract class BaseQuotaProvider(
    final override val service: AiService,
    protected val prefsManager: EncryptedPrefsManager
) : QuotaProvider {
    override fun accounts(): List<ServiceAccount> = prefsManager.getAccounts(service)

    protected abstract suspend fun fetch(account: ServiceAccount): Result<QuotaInfo, AppError>
    final override suspend fun fetchQuota(account: ServiceAccount): Result<QuotaInfo, AppError> =
        guardedFetch { fetch(account) }.map { it.forAccount(account) }

    suspend fun fetchQuota(): Result<QuotaInfo, AppError> {
        val account = accounts().firstOrNull()
            ?: return Result.Failure(AppError.CredentialNotFound(service))
        return fetchQuota(account)
    }

    suspend fun validateAccount(accountId: String): Result<Unit, AppError> {
        val account = accounts().find { it.id == accountId }
            ?: return Result.Failure(AppError.CredentialNotFound(service))
        return validate(account)
    }

    suspend fun validateCredential(): Result<Unit, AppError> {
        val account = accounts().firstOrNull()
            ?: return Result.Failure(AppError.CredentialNotFound(service))
        return validate(account)
    }

    protected fun saveRefreshed(
        account: ServiceAccount,
        credential: Credential
    ) {
        prefsManager.updateAccountCredential(service, account.id, credential)
    }

    protected suspend fun <C : Credential> refreshSerialized(
        accountId: String,
        current: C,
        exchange: suspend () -> C?
    ): C? = TokenRefreshLock.withLock {
        val stored =
            prefsManager.getAccounts(service).firstOrNull { it.id == accountId }?.credential
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
