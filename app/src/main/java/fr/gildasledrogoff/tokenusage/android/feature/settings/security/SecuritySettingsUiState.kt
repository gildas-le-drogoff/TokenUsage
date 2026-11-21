package fr.gildasledrogoff.tokenusage.feature.settings.security

data class SecuritySettingsUiState(
    val appLockEnabled: Boolean = false,
    val demoDataAvailable: Boolean = false,
    val demoDataEnabled: Boolean = false,
    val showDeleteConfirmDialog: Boolean = false
)
