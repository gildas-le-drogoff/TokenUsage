package fr.gildasledrogoff.tokenusage.core.domain.repository

import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ZaiAccount


interface ZaiRepository : MultiAccountQuotaRepository {

    
    suspend fun fetchQuotaForAccount(account: ZaiAccount): Result<QuotaInfo, AppError>
}
