package fr.gildasledrogoff.tokenusage.core.domain.repository

import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.ClaudeAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result


interface ClaudeQuotaRepository : MultiAccountQuotaRepository {

    suspend fun fetchQuotaForAccount(account: ClaudeAccount): Result<QuotaInfo, AppError>
}
