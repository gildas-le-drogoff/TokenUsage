package fr.gildasledrogoff.tokenusage.core.alert

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import kotlinx.serialization.Serializable

/**
 * Alert rule of one account (or the default of one service).
 *
 * A `null` threshold disables the matching alert.
 */
@Serializable
data class AlertRule(
    val enabled: Boolean = true,
    /** Usage fraction (0..1) of a quota window from which an alert is raised. */
    val quotaThreshold: Double? = DEFAULT_QUOTA_THRESHOLD,
    /** Window the quota/depletion alerts apply to; `null` = every window. */
    val quotaWindowLabel: String? = null,
    /** Remaining balance (in the account currency) at or under which an alert is raised. */
    val balanceThreshold: Double? = null,
    /** Raise an alert when the predicted depletion falls within this many hours. */
    val depletionHorizonHours: Int? = null
) {
    companion object {
        const val DEFAULT_QUOTA_THRESHOLD = 0.8
    }
}

/** All the alert rules: one default per service, overridden per account. */
@Serializable
data class AlertConfig(
    /** Keyed by [AiService.name]. */
    val serviceDefaults: Map<String, AlertRule> = emptyMap(),
    /** Keyed by the account key (`"SERVICE:accountId"`, see [QuotaInfo.accountKey]). */
    val accountRules: Map<String, AlertRule> = emptyMap(),
    /** Lookback of the consumption history used for the predictions. */
    val forecastWindowDays: Int = DEFAULT_FORECAST_WINDOW_DAYS
) {
    fun ruleFor(service: AiService, accountKey: String): AlertRule =
        accountRules[accountKey] ?: serviceDefaults[service.name] ?: AlertRule()

    fun ruleFor(quota: QuotaInfo): AlertRule = ruleFor(quota.service, quota.accountKey)

    companion object {
        const val DEFAULT_FORECAST_WINDOW_DAYS = 7
    }
}

/** Account key shared with the dashboard and the diagnostics (`"SERVICE:accountId"`). */
fun accountKeyOf(service: AiService, accountId: String?): String =
    "${service.name}:${accountId ?: "default"}"
