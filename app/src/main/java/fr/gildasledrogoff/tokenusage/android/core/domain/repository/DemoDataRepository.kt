package fr.gildasledrogoff.tokenusage.core.domain.repository

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.DeepseekAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result


interface DemoDataRepository {

    
    val isAvailable: Boolean

    
    fun isEnabled(): Boolean

    fun setEnabled(enabled: Boolean)

    
    val supportedServices: Set<AiService>

    
    suspend fun sampleQuotas(): List<DemoQuota>

    
    fun sampleDeepseekAccounts(): List<DeepseekAccount>

    
    val history: BalanceHistoryRepository
}


data class DemoQuota(
    val service: AiService,
    val accountLabel: String?,
    val result: Result<QuotaInfo, AppError>
)
