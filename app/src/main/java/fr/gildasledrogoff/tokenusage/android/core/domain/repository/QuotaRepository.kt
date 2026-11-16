package fr.gildasledrogoff.tokenusage.core.domain.repository

import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result

interface QuotaRepository {
    suspend fun fetchQuota(): Result<QuotaInfo, AppError>
    suspend fun validateCredential(): Result<Unit, AppError>
}


interface MultiAccountQuotaRepository : QuotaRepository {
    suspend fun validateAccount(accountId: String): Result<Unit, AppError>
}
