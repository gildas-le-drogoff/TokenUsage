package fr.gildasledrogoff.tokenusage.core.provider

import androidx.annotation.StringRes
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential

/** How an account of a service is signed in, beyond typing credentials by hand. */
enum class LoginMethod {
    /** In-app web login capturing a session cookie (Claude). */
    WEB_SESSION,

    /** OAuth device flow (GitHub Copilot). */
    DEVICE_FLOW
}

/**
 * A "Sign in" button on each account. The token it obtains is stored in the field
 * [targetFieldKey] and the account is validated.
 */
data class LoginAction(
    val method: LoginMethod,
    @StringRes val labelRes: Int,
    val targetFieldKey: String,
    @StringRes val hintRes: Int? = null
)

/**
 * One input of the account editor.
 *
 * @property secret masked input, never pre-filled with the stored value.
 * @property readOnly informational value (e.g. token expiry), not editable.
 * @property advanced hidden behind [CredentialForm.advancedToggleRes] until asked for.
 */
data class CredentialField(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val hintRes: Int? = null,
    val secret: Boolean = true,
    val readOnly: Boolean = false,
    val advanced: Boolean = false
)

/**
 * Describes the account editor of a service and turns what the user typed into a [Credential].
 *
 * @property hintRes explanation shown above the accounts.
 * @property emptyRes text shown when the service has no account yet (null = generic text).
 * @property login optional per-account sign-in button.
 * @property advancedToggleRes label of the toggle revealing the [CredentialField.advanced] fields.
 * @property options service-wide switches shown with the accounts (see [QuotaProvider.optionValue]).
 * @property assemble builds the credential from the edited [fields] values (blank = unchanged)
 *   and the previously stored one; null while the input is incomplete.
 * @property prefill non-secret values to show for an already stored credential.
 */
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
    /** Keys that must be re-typed to replace a secret. */
    val secretKeys: Set<String> get() = fields.filter { it.secret }.map { it.key }.toSet()
}

/**
 * A service-wide on/off setting (not tied to an account).
 *
 * @property hintWhenOnRes replaces the form hint while the option is on, if set.
 */
data class ProviderOption(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val hintWhenOnRes: Int? = null
)

/** Shortcut for the many services authenticated by a single API key typed by the user. */
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
