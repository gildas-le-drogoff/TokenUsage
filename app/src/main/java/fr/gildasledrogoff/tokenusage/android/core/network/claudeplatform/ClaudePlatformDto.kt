package fr.gildasledrogoff.tokenusage.core.network.claudeplatform

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object ClaudePlatformDto {
    const val CAPABILITY_API = "api"
    const val PROGRAM_SUBSCRIPTION = "subscription_api_credit"
    const val DEFAULT_CURRENCY = "USD"
    const val DEFAULT_EXPONENT = 2
    const val MAX_EXPONENT = 4

    @Serializable
    data class Organization(
        val uuid: String? = null,
        val name: String? = null,
        val capabilities: List<String> = emptyList()
    ) {
        val isApi: Boolean get() = CAPABILITY_API in capabilities
    }

    @Serializable
    data class Credits(
        val amount: Long? = null,
        val currency: String? = null,
        val balance: Balance? = null,
        @SerialName("promo_tranches") val promoTranches: List<Tranche>? = null,
        val tranches: List<Tranche>? = null
    )

    @Serializable
    data class Balance(val credits: BalanceCredits? = null)

    @Serializable
    data class BalanceCredits(val exponent: Int? = null)

    @Serializable
    data class Tranche(
        val name: String? = null,
        @SerialName("program_id") val programId: String? = null,
        @SerialName("granted_amount_minor_units") val granted: Long? = null,
        @SerialName("remaining_amount_minor_units") val remaining: Long? = null,
        @SerialName("expires_at") val expiresAt: String? = null
    )

    @Serializable
    data class Spend(val amount: Long? = null)
}
