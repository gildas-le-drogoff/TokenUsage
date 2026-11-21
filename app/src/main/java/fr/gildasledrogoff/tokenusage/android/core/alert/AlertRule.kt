package fr.gildasledrogoff.tokenusage.core.alert

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import kotlinx.serialization.Serializable

@Serializable
data class AlertRule(
    val enabled: Boolean = true,

    val quotaThreshold: Double? = DEFAULT_QUOTA_THRESHOLD,

    val quotaWindowLabel: String? = null,

    val balanceThreshold: Double? = null,

    val depletionHorizonHours: Int? = null
) {
    companion object {
        const val DEFAULT_QUOTA_THRESHOLD = 0.8
    }
}

@Serializable
data class AlertConfig(

    val serviceDefaults: Map<String, AlertRule> = emptyMap(),

    val accountRules: Map<String, AlertRule> = emptyMap(),

    val forecastWindowDays: Int = DEFAULT_FORECAST_WINDOW_DAYS,

    val warnBeforeReset: Boolean = true,

    val celebrateResets: Boolean = true
) {
    fun ruleFor(service: AiService, accountKey: String): AlertRule =
        accountRules[accountKey] ?: serviceDefaults[service.name] ?: AlertRule()

    fun ruleFor(quota: QuotaInfo): AlertRule = ruleFor(quota.service, quota.accountKey)

    companion object {
        const val DEFAULT_FORECAST_WINDOW_DAYS = 7
    }
}

fun accountKeyOf(service: AiService, accountId: String?): String =
    "${service.name}:${accountId ?: "default"}"
