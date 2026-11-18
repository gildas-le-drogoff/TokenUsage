package fr.gildasledrogoff.tokenusage.feature.settings

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.DisplayCurrency
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.LockScreenVisibility
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode

data class SettingsUiState(
    val serviceStates: Map<AiService, ServiceCredentialState> = AiService.entries.associateWith {
        ServiceCredentialState()
    },
    val claudeAccounts: List<ClaudeAccountUi> = emptyList(),
    val deepseekAccounts: List<DeepseekAccountUi> = emptyList(),
    val zaiAccounts: List<ZaiAccountUi> = emptyList(),
    val zaiHideMonthlyTools: Boolean = true,

    val copilotLogin: CopilotLoginUi? = null,
    val currencyConversion: CurrencyConversionUi = CurrencyConversionUi(),
    val refreshIntervalMinutes: Long = 30L,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val quotaDisplayMode: QuotaDisplayMode = QuotaDisplayMode.REMAINING,
    val lockScreenVisibility: LockScreenVisibility = LockScreenVisibility.SYSTEM,
    val notificationsEnabled: Boolean = true,

    val appLockEnabled: Boolean = false,

    val demoDataAvailable: Boolean = false,
    val demoDataEnabled: Boolean = false,
    val showDeleteConfirmDialog: Boolean = false,
    val export: ExportUiState = ExportUiState(),
    val importState: ImportUiState = ImportUiState()
)


sealed interface ImportError {

    data object NotABackup : ImportError

    data object WrongPin : ImportError

    data object UnsupportedVersion : ImportError
    data object Failed : ImportError

    data object QrMismatch : ImportError
}

data class QrScanProgress(
    val total: Int,
    val received: Set<Int>
) {
    val nextIndex: Int get() = (1..total).firstOrNull { it !in received } ?: total
}


data class ImportUiState(

    val awaitingPin: Boolean = false,
    val pin: String = "",
    val isBusy: Boolean = false,
    val error: ImportError? = null,
    val scanSession: QrScanProgress? = null,

    val importedCount: Int? = null,

    val importedHistoryEntries: Int = 0
)


sealed interface ExportError {
    data object PinTooShort : ExportError
    data object PinMismatch : ExportError
    data object QrTooLarge : ExportError
    data object Failed : ExportError
}


data class ExportUiState(
    val pin: String = "",
    val confirmPin: String = "",
    val isBusy: Boolean = false,
    val error: ExportError? = null,

    val zipReady: Boolean = false,
    val zipFileName: String = "",

    val qrCodes: List<String>? = null,
    val qrIndex: Int = 0
)


data class CurrencyConversionUi(
    val displayCurrency: DisplayCurrency = DisplayCurrency.ORIGINAL,
    val source: FxRateSource = FxRateSource.DEFAULT,

    val apiFetchAllowed: Boolean = false,

    val manualUsdEurText: String = "",
    val manualUsdEurInvalid: Boolean = false,

    val manualUsdCnyText: String = "",
    val manualUsdCnyInvalid: Boolean = false,
    val isFetching: Boolean = false,

    val remoteRates: List<FxRate> = emptyList(),
    val fetchError: AppError? = null
)


data class ClaudeAccountUi(
    val id: String,
    val label: String = "",
    val sessionKey: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val isValidating: Boolean = false,
    val validationResult: ValidationResult? = null,

    val hasStoredCredential: Boolean = false,

    val credentialEdited: Boolean = false
) {
    val isConfigured: Boolean get() =
        hasStoredCredential || sessionKey.isNotBlank() || accessToken.isNotBlank()
}

data class DeepseekAccountUi(
    val id: String,
    val label: String = "",

    val apiKey: String = "",
    val hasStoredKey: Boolean = false,
    val isValidating: Boolean = false,
    val validationResult: ValidationResult? = null
)

data class ZaiAccountUi(
    val id: String,
    val label: String = "",

    val apiKey: String = "",
    val hasStoredKey: Boolean = false,
    val isValidating: Boolean = false,
    val validationResult: ValidationResult? = null
)


data class CopilotLoginUi(
    val userCode: String = "",
    val verificationUri: String = "",
    val error: AppError? = null
)


data class ServiceCredentialState(
    val accessToken: String = "",
    val refreshToken: String = "",
    val sessionKey: String = "",
    val accountId: String = "",
    val oauthClientId: String = "",
    val oauthClientSecret: String = "",
    val expiresAtDisplay: String = "",

    val isStored: Boolean = false,
    val isValidating: Boolean = false,
    val validationResult: ValidationResult? = null
)

sealed class ValidationResult {
    data object Success : ValidationResult()

    data class Failure(val error: AppError) : ValidationResult()
}
