package fr.gildasledrogoff.tokenusage.feature.settings.accounts

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.provider.CredentialForm

data class AccountsUiState(

    val accounts: Map<AiService, List<AccountUi>> = emptyMap(),

    val options: Map<String, Boolean> = emptyMap(),
    val deviceLogin: DeviceLoginUi? = null
) {
    fun option(service: AiService, key: String): Boolean = options[optionKey(service, key)] ?: false

    companion object {
        fun optionKey(service: AiService, key: String) = "${service.name}.$key"
    }
}

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
