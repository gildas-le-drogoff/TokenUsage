package fr.gildasledrogoff.tokenusage.core.alert
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import java.time.Instant
enum class AlertType { QUOTA_THRESHOLD, LOW_BALANCE, DEPLETION_FORECAST }

data class QuotaAlert(
    val accountKey: String,
    val service: AiService,
    val accountLabel: String?,
    val type: AlertType,
    
    val windowLabel: String? = null,
    
    val currentValue: Double,
    
    val threshold: Double? = null,
    val currency: String? = null,
    val depletionAt: Instant? = null
)
