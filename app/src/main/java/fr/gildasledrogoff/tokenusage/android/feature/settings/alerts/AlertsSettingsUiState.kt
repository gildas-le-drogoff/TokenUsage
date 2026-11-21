package fr.gildasledrogoff.tokenusage.feature.settings.alerts
import fr.gildasledrogoff.tokenusage.core.alert.AlertRule
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService

data class AlertAccountUi(
    
    val key: String,
    
    val label: String?,
    
    val number: Int,
    
    val rule: AlertRule,
    
    val customized: Boolean
)
data class AlertServiceUi(
    val service: AiService,
    val defaultRule: AlertRule,
    val accounts: List<AlertAccountUi>,
    
    val windowLabels: List<String>
) {
    
    val hasSeveralAccounts: Boolean get() = accounts.size > 1
    
    val supportsBalance: Boolean get() = service in BALANCE_SERVICES
    companion object {
        val BALANCE_SERVICES = setOf(AiService.DEEPSEEK, AiService.OPENROUTER)
    }
}
data class AlertsSettingsUiState(
    val services: List<AlertServiceUi> = emptyList(),
    
    val notificationsEnabled: Boolean = true
)
