package fr.gildasledrogoff.tokenusage.feature.settings.refresh

import fr.gildasledrogoff.tokenusage.core.status.StatusCheckSettings

data class RefreshSettingsUiState(
    val refreshIntervalMinutes: Long = 30L,
    val notificationsEnabled: Boolean = true,
    val reconnectRefreshEnabled: Boolean = false,
    val statusCheck: StatusCheckSettings = StatusCheckSettings()
)
