package fr.gildasledrogoff.tokenusage.core.provider

import androidx.annotation.StringRes
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential

enum class LoginMethod {

    WEB_SESSION,

    DEVICE_FLOW
}

data class LoginAction(
    val method: LoginMethod,
    @StringRes val labelRes: Int,
    val targetFieldKey: String,
    @StringRes val hintRes: Int? = null
)

data class CredentialField(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val hintRes: Int? = null,
    val secret: Boolean = true,
    val readOnly: Boolean = false,
    val advanced: Boolean = false
)

class CredentialForm(
    val fields: List<CredentialField>,
    @StringRes val hintRes: Int? = null,
    @StringRes val emptyRes: Int? = null,
    val login: LoginAction? = null,
    @StringRes val advancedToggleRes: Int? = null,
    val options: List<ProviderOption> = emptyList(),
    val assemble: (values: Map<String, String>, previous: Credential?) -> Credential?,
    val prefill: (Credential) -> Map<String, String> = { emptyMap() }
) {

    val secretKeys: Set<String> get() = fields.filter { it.secret }.map { it.key }.toSet()
}

data class ProviderOption(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val hintWhenOnRes: Int? = null
)

fun apiKeyForm(
    @StringRes labelRes: Int,
    @StringRes fieldHintRes: Int,
    @StringRes hintRes: Int? = null,
    @StringRes emptyRes: Int? = null,
    login: LoginAction? = null,
    options: List<ProviderOption> = emptyList(),
    toCredential: (String) -> Credential
) = CredentialForm(
    fields = listOf(CredentialField(KEY_API_KEY, labelRes, fieldHintRes)),
    hintRes = hintRes,
    emptyRes = emptyRes,
    login = login,
    options = options,
    assemble = { values, _ ->
        values[KEY_API_KEY]?.trim()?.takeIf { it.isNotEmpty() }?.let(toCredential)
    }
)

const val KEY_API_KEY = "apiKey"
