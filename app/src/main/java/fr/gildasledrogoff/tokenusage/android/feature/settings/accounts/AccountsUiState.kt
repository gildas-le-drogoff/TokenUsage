package fr.gildasledrogoff.tokenusage.feature.settings.accounts

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.provider.CredentialForm

data class AccountsUiState(
    /** Accounts being edited, per service (stored ones and not-yet-complete drafts). */
    val accounts: Map<AiService, List<AccountUi>> = emptyMap(),
    /** Service-wide switches, keyed by [optionKey]. */
    val options: Map<String, Boolean> = emptyMap(),
    val deviceLogin: DeviceLoginUi? = null
) {
    fun option(service: AiService, key: String): Boolean = options[optionKey(service, key)] ?: false

    companion object {
        fun optionKey(service: AiService, key: String) = "${service.name}.$key"
    }
}

/**
 * One account in the editor.
 *
 * @property values what the [CredentialForm] fields currently hold: typed secrets (never the
 *   stored ones) and the non-secret values of the stored credential.
 * @property hasStoredCredential a credential is saved for this account.
 * @property credentialEdited the user typed in a credential field since the last save.
 */
data class AccountUi(
    val id: String,
    val label: String = "",
    val values: Map<String, String> = emptyMap(),
    val hasStoredCredential: Boolean = false,
    val credentialEdited: Boolean = false,
    val isValidating: Boolean = false,
    val validationResult: ValidationResult? = null
) {
    fun isConfigured(form: CredentialForm): Boolean =
        hasStoredCredential || form.secretKeys.any { !values[it].isNullOrBlank() }
}

/** State of the device-flow dialog; [service]/[accountId] say where the token will be stored. */
data class DeviceLoginUi(
    val service: AiService,
    val accountId: String,
    val userCode: String = "",
    val verificationUri: String = "",
    val error: AppError? = null
)

sealed class ValidationResult {
    data object Success : ValidationResult()

    data class Failure(val error: AppError) : ValidationResult()
}
