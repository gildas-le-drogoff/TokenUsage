package fr.gildasledrogoff.tokenusage.feature.dashboard

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.BalancePace
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionPoint
import fr.gildasledrogoff.tokenusage.core.domain.model.DashboardInsight
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.InsightVisibility
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import fr.gildasledrogoff.tokenusage.core.domain.model.UsagePace
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageSeverity
import java.time.Instant

sealed class DashboardUiState {
    data object Loading : DashboardUiState()
    data class Success(
        val cards: List<ServiceCardData>,
        val lastUpdated: Instant
    ) : DashboardUiState()

    data class PartialSuccess(
        val cards: List<ServiceCardData>,
        val errors: Map<AiService, AppError>,
        val lastUpdated: Instant
    ) : DashboardUiState()

    /**
     * Aucun réseau : les cartes montrent les dernières données reçues.
     * @param lastUpdated date des données les plus récentes affichées
     */
    data class Offline(
        val cards: List<ServiceCardData>,
        val lastUpdated: Instant
    ) : DashboardUiState()

    data class Error(val error: AppError, val offline: Boolean = false) : DashboardUiState()
}

fun DashboardUiState.withInsightVisibility(visibility: InsightVisibility): DashboardUiState =
    when (this) {
        is DashboardUiState.Success -> copy(cards = cards.map { it.withInsightVisibility(visibility) })
        is DashboardUiState.PartialSuccess ->
            copy(cards = cards.map { it.withInsightVisibility(visibility) })

        is DashboardUiState.Offline -> copy(cards = cards.map { it.withInsightVisibility(visibility) })
        DashboardUiState.Loading, is DashboardUiState.Error -> this
    }

fun ServiceCardData.withInsightVisibility(visibility: InsightVisibility): ServiceCardData = copy(
    showPace = visibility.isShown(DashboardInsight.PACE, service, accountId),
    showCurves = visibility.isShown(DashboardInsight.CURVES, service, accountId)
)

data class ServiceCardData(
    val service: AiService,
    val windows: List<UsageWindowUi>,
    val extraUsage: ExtraUsageUi?,
    val tier: String?,
    val accountBalances: List<AccountBalanceUi> = emptyList(),
    val weeklyBreakdown: List<UsageBreakdownRowUi> = emptyList(),
    val spending: List<SpendRowUi> = emptyList(),
    val accountLabel: String? = null,
    val accountNumber: Int? = null,

    val accountId: String? = null,

    val hasConsumptionHistory: Boolean = false,
    val isLoading: Boolean = false,
    val error: AppError? = null,
    val staleSince: Instant? = null,
    val showPace: Boolean = true,
    val showCurves: Boolean = true
) {
    val hasData: Boolean get() = error == null || staleSince != null
}

data class UsageWindowUi(
    val label: String,
    val utilization: Double,
    val resetsAt: Instant? = null,
    val severity: UsageSeverity = UsageSeverity.NORMAL,
    val windowMinutes: Int? = null,
    val historicalPace: UsagePace? = null
)

data class UsageBreakdownRowUi(
    val label: String,
    val percent: Double
)

data class SpendRowUi(
    val period: SpendPeriod,
    val amount: Double,
    val currency: String,
    val rate: FxRate? = null
)

data class ExtraUsageUi(
    val monthlyLimit: Double,
    val usedCredits: Double,
    val utilization: Double,
    val currency: String,
    val rate: FxRate? = null
)

sealed interface ConsumptionChartUiState {
    data object Idle : ConsumptionChartUiState
    data object Loading : ConsumptionChartUiState
    data class Ready(
        val accounts: List<AccountConsumptionUi>,
        val selectedAccountId: String
    ) : ConsumptionChartUiState {
        val selected: AccountConsumptionUi? get() = accounts.find { it.accountId == selectedAccountId }
    }
}

data class AccountConsumptionUi(
    val accountId: String,
    val label: String,
    val points: List<ConsumptionPoint>,
    val currency: String
)

data class AccountBalanceUi(
    val label: String,
    val currency: String,
    val totalBalance: Double,
    val grantedBalance: Double?,
    val toppedUpBalance: Double?,
    val isAvailable: Boolean,
    val rate: FxRate? = null,
    val pace: BalancePace? = null,
    val error: AppError? = null
)
