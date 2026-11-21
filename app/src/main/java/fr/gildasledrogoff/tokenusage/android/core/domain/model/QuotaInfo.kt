package fr.gildasledrogoff.tokenusage.core.domain.model
import java.time.Instant
data class QuotaInfo(
    val service: AiService,
    val windows: List<UsageWindow>,
    val extraUsage: ExtraUsage? = null,
    val tier: String? = null,
    val accountBalances: List<AccountBalance> = emptyList(),
    val weeklyBreakdown: List<UsageBreakdownRow> = emptyList(),
    val spending: List<SpendAmount> = emptyList(),
    val fetchedAt: Instant = Instant.now(),
    
    val accountId: String? = null,
    
    val accountLabel: String? = null
) {
    
    val accountKey: String get() = "${service.name}:${accountId ?: "default"}"
}
data class CreditBalance(
    val currency: String,
    val totalBalance: Double,
    val grantedBalance: Double?,
    val toppedUpBalance: Double?,
    val isAvailable: Boolean
)
data class AccountBalance(
    val label: String,
    val balance: CreditBalance?,
    val error: AppError? = null
)
enum class UsageSeverity { NORMAL, WARNING, CRITICAL }
data class UsageWindow(
    val label: String,
    val utilization: Double,
    val resetsAt: Instant?,
    val severity: UsageSeverity = UsageSeverity.NORMAL
)
data class UsageBreakdownRow(
    val label: String,
    val percent: Double
)
data class ExtraUsage(
    val isEnabled: Boolean,
    val monthlyLimit: Double,
    val usedCredits: Double,
    val utilization: Double,
    val currency: String
)
enum class SpendPeriod { TODAY, THIS_WEEK, THIS_MONTH, LAST_7_DAYS, LAST_30_DAYS }
data class SpendAmount(
    val period: SpendPeriod,
    val amount: Double,
    val currency: String
)
