package fr.gildasledrogoff.tokenusage.feature.settings.accounts

import android.app.Application
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.core.provider.CredentialField
import fr.gildasledrogoff.tokenusage.core.provider.CredentialForm
import fr.gildasledrogoff.tokenusage.core.provider.LoginAction
import fr.gildasledrogoff.tokenusage.core.provider.LoginMethod
import fr.gildasledrogoff.tokenusage.core.provider.ProviderOption
import fr.gildasledrogoff.tokenusage.core.provider.QuotaProvider
import fr.gildasledrogoff.tokenusage.ui.theme.TokenUsageTheme
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    application = Application::class,
    qualifiers = "en-w411dp-h891dp"
)
class ProviderAccountsSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private val form = CredentialForm(
        fields = listOf(
            CredentialField("apiKey", R.string.field_api_key),
            CredentialField("accountId", R.string.field_account_id_optional, secret = false, advanced = true)
        ),
        hintRes = R.string.zai_hint_no_tools,
        login = LoginAction(LoginMethod.DEVICE_FLOW, R.string.copilot_sign_in, "apiKey"),
        options = listOf(ProviderOption("hideTools", R.string.zai_hide_monthly_tools)),
        assemble = { values, _ -> values["apiKey"]?.let { Credential.ApiKeyCredential(it) } }
    )

    private val provider = object : QuotaProvider {
        override val service = AiService.ZAI
        override val form = this@ProviderAccountsSectionTest.form
        override fun accounts(): List<ServiceAccount> = emptyList()
        override suspend fun fetchQuota(account: ServiceAccount): Result<QuotaInfo, AppError> =
            error("unused")
    }

    private fun show(accounts: List<AccountUi>, expand: Boolean = true) {
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                ProviderAccountsSection(
                    provider = provider,
                    accounts = accounts,
                    optionValue = { false },
                    onAddAccount = { events += "add" },
                    onRemoveAccount = { events += "remove:$it" },
                    onFieldChange = { id, key, value -> events += "field:$id:$key=$value" },
                    onValidate = { events += "validate:$it" },
                    onOptionChange = { key, value -> events += "option:$key=$value" },
                    onSignIn = { events += "signIn:$it" }
                )
            }
        }
        if (expand) compose.onNodeWithText("Z.ai").performClick()
    }

    @Test
    fun `collapsed header shows the service and its usage`() {
        show(listOf(AccountUi("a1", hasStoredCredential = true), AccountUi("a2")), expand = false)

        compose.onNodeWithText("Z.ai").assertIsDisplayed()
        compose.onNodeWithText("1 account in use").assertIsDisplayed()
        compose.onNodeWithText("Add account").assertDoesNotExist()
    }

    @Test
    fun `an unused provider says so`() {
        show(emptyList(), expand = false)

        compose.onNodeWithText("Not in use").assertIsDisplayed()
    }

    @Test
    fun `expanding without account shows the hint, option and empty message`() {
        show(emptyList())

        compose.onNodeWithText("Remaining quota per account", substring = true).assertIsDisplayed()
        compose.onNodeWithText("No account yet", substring = true).assertIsDisplayed()
        compose.onNode(isToggleable() and hasText("Hide “Monthly Tools”", substring = true)).assertIsOff()

        compose.onNodeWithText("Add account").performClick()

        assertEquals(listOf("add"), events)
    }

    @Test
    fun `toggling an option is reported`() {
        show(emptyList())

        compose.onNodeWithText("Hide “Monthly Tools”", substring = true).performClick()

        assertEquals(listOf("option:hideTools=true"), events)
    }

    @Test
    fun `accounts are named by label or number`() {
        show(listOf(AccountUi("a1", label = "Work"), AccountUi("a2")))

        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("Account 2").assertIsDisplayed()
    }

    @Test
    fun `validation needs a credential`() {
        show(listOf(AccountUi("a1")))

        compose.onNodeWithText("Validate").assertIsNotEnabled()
    }

    @Test
    fun `a stored credential can be validated without retyping it`() {
        show(listOf(AccountUi("a1", hasStoredCredential = true)))

        compose.onNodeWithText("Saved, never shown again", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Validate").assertIsEnabled().performClick()

        assertEquals(listOf("validate:a1"), events)
    }

    @Test
    fun `validation results are shown`() {
        show(
            listOf(
                AccountUi("a1", label = "Ok", hasStoredCredential = true, validationResult = ValidationResult.Success),
                AccountUi(
                    "a2",
                    label = "Ko",
                    hasStoredCredential = true,
                    validationResult = ValidationResult.Failure(AppError.RateLimited)
                )
            )
        )

        compose.onNodeWithText("Valid").assertIsDisplayed()
        compose.onNodeWithText("Rate limited", substring = true).assertIsDisplayed()
    }

    @Test
    fun `device flow sign in is delegated`() {
        show(listOf(AccountUi("a1")))

        compose.onNodeWithText("Sign in with GitHub").performClick()

        assertEquals(listOf("signIn:a1"), events)
    }

    @Test
    fun `advanced fields are hidden until requested`() {
        show(listOf(AccountUi("a1")))
        compose.onNodeWithText("Account ID (optional)").assertDoesNotExist()

        compose.onNodeWithText("Manual tokens (alternative)").performClick()
        compose.onNode(hasSetTextAction() and hasText("Account ID (optional)")).performTextReplacement("acct-9")

        assertEquals(listOf("field:a1:accountId=acct-9"), events)
    }

    @Test
    fun `filled advanced fields are shown right away`() {
        show(listOf(AccountUi("a1", values = mapOf("accountId" to "acct-1"))))

        compose.onNodeWithText("acct-1").assertIsDisplayed()
    }

    @Test
    fun `removing an account asks for confirmation`() {
        show(listOf(AccountUi("a1")))

        compose.onNodeWithContentDescription("Remove account").performClick()
        compose.onNodeWithText("Confirm deletion?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), events)

        compose.onNodeWithContentDescription("Remove account").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf("remove:a1"), events)
    }

    @Ignore("Compose never gets idle once the rename dialog is open (even with a paused clock): to investigate, see TODO.md")
    @Test
    fun `renaming an account updates its label`() {
        show(listOf(AccountUi("a1", label = "Old")))

        compose.onNodeWithContentDescription("Rename this account", substring = true).performClick()
        compose.onNodeWithText("Rename account").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Old")).performTextReplacement("New")
        compose.onNode(hasClickAction() and hasText("Rename")).performClick()

        assertEquals(listOf("field:a1:label=New"), events)
    }
}
