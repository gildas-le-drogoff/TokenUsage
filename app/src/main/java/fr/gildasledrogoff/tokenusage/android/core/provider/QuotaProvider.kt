package fr.gildasledrogoff.tokenusage.core.provider
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount

interface QuotaProvider {
    val service: AiService
    
    val form: CredentialForm
    
    fun accounts(): List<ServiceAccount>
    
    suspend fun fetchQuota(account: ServiceAccount): Result<QuotaInfo, AppError>
    
    val tracksBalanceHistory: Boolean get() = false
    
    fun optionValue(key: String): Boolean = false
    fun setOption(key: String, value: Boolean) = Unit
    
    suspend fun validate(account: ServiceAccount): Result<Unit, AppError> =
        fetchQuota(account).map { }
}
