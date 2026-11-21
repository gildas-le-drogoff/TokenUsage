package fr.gildasledrogoff.tokenusage.core.alert

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import java.time.Instant

enum class AlertType { QUOTA_THRESHOLD, LOW_BALANCE, DEPLETION_FORECAST }

/** An alert to show: one per account and per [AlertType]. */
data class QuotaAlert(
    val accountKey: String,
    val service: AiService,
    val accountLabel: String?,
    val type: AlertType,
    /** Quota window concerned (`null` for a balance). */
    val windowLabel: String? = null,
    /** Usage fraction (0..1) or balance, depending on [type]. */
    val currentValue: Double,
    /** Configured threshold (usage fraction or balance); `null` for a forecast alert. */
    val threshold: Double? = null,
    val currency: String? = null,
    val depletionAt: Instant? = null
)
