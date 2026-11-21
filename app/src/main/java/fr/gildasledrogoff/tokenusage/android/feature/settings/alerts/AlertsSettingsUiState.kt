package fr.gildasledrogoff.tokenusage.feature.settings.alerts

import fr.gildasledrogoff.tokenusage.core.alert.AlertRule
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService

/** One account listed in the alert settings. */
data class AlertAccountUi(
    /** `"SERVICE:accountId"`. */
    val key: String,
    /** Label given by the user, `null` if none. */
    val label: String?,
    /** 1-based rank among the accounts of its service, to name the unlabelled ones. */
    val number: Int,
    /** Rule applied to this account (its own, or the service default). */
    val rule: AlertRule,
    /** `true` when the account has its own rule instead of the service default. */
    val customized: Boolean
)

data class AlertServiceUi(
    val service: AiService,
    val defaultRule: AlertRule,
    val accounts: List<AlertAccountUi>,
    /** Quota windows seen at the last refresh, to restrict an alert to one of them. */
    val windowLabels: List<String>
) {
    /** Individual rules are only offered when there are several accounts to tell apart. */
    val hasSeveralAccounts: Boolean get() = accounts.size > 1

    /** Only some services report a remaining balance. */
    val supportsBalance: Boolean get() = service in BALANCE_SERVICES

    companion object {
        val BALANCE_SERVICES = setOf(AiService.DEEPSEEK, AiService.OPENROUTER)
    }
}

data class AlertsSettingsUiState(
    val services: List<AlertServiceUi> = emptyList(),
    /** Alerts are shown with the app notifications: they do nothing while those are off. */
    val notificationsEnabled: Boolean = true
)
