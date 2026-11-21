package fr.gildasledrogoff.tokenusage.feature.settings.refresh
data class RefreshSettingsUiState(
    val refreshIntervalMinutes: Long = 30L,
    val notificationsEnabled: Boolean = true
)
