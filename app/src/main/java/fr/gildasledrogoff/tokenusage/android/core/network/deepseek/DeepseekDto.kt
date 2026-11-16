package fr.gildasledrogoff.tokenusage.core.network.deepseek

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object DeepseekDto {

    
    @Serializable
    data class BalanceResponse(
        @SerialName("is_available") val isAvailable: Boolean? = null,
        @SerialName("balance_infos") val balanceInfos: List<BalanceInfo> = emptyList()
    )

    
    @Serializable
    data class BalanceInfo(
        @SerialName("currency") val currency: String? = null,
        @SerialName("total_balance") val totalBalance: String? = null,
        @SerialName("granted_balance") val grantedBalance: String? = null,
        @SerialName("topped_up_balance") val toppedUpBalance: String? = null
    )
}
