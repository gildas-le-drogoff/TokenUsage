package fr.gildasledrogoff.tokenusage.feature.history

import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionPoint
import fr.gildasledrogoff.tokenusage.core.domain.model.DeepseekAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import java.time.Instant

data class BalanceHistoryUiState(
    val accounts: List<DeepseekAccount> = emptyList(),
    val selectedAccountId: String? = null,
    val granularity: ConsumptionGranularity = ConsumptionGranularity.DAILY,
    val points: List<ConsumptionPoint> = emptyList(),
    val latestBalance: Double? = null,
    val currency: String = "",
    val fxRate: FxRate? = null,

    val depletionAt: Instant? = null,

    val depletionEstimated: Boolean = false,
    val isLoading: Boolean = true
)
