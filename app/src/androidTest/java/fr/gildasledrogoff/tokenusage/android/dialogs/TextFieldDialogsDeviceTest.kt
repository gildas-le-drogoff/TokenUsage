package fr.gildasledrogoff.tokenusage.dialogs

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
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
import fr.gildasledrogoff.tokenusage.core.provider.QuotaProvider
import fr.gildasledrogoff.tokenusage.feature.settings.accounts.AccountUi
import fr.gildasledrogoff.tokenusage.feature.settings.accounts.ProviderAccountsSection
import fr.gildasledrogoff.tokenusage.feature.settings.security.DeleteConfirmDialog
import fr.gildasledrogoff.tokenusage.ui.theme.TokenUsageTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test


class TextFieldDialogsDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun s(id: Int) = context.getString(id)
    private val events = mutableListOf<String>()

    private fun showDeleteDialog() {
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                DeleteConfirmDialog(onConfirm = { events += "confirm" }, onDismiss = { events += "dismiss" })
            }
        }
    }

    @Test
    fun deleteDialogExplainsWhatWillBeDeleted() {
        showDeleteDialog()

        compose.onNodeWithText(s(R.string.action_delete_all)).assertExists()
        compose.onNodeWithText(s(R.string.delete_dialog_text)).assertExists()
        compose.onNodeWithText(s(R.string.field_type_delete)).assertExists()
    }

    @Test
    fun deleteConfirmIsDisabledUntilDeleteIsTyped() {
        showDeleteDialog()

        compose.onNodeWithText(s(R.string.action_delete)).assertIsNotEnabled()
    }

    @Test
    fun wrongConfirmationWordKeepsConfirmDisabled() {
        showDeleteDialog()

        compose.onNode(hasSetTextAction()).performTextInput("delete")

        compose.onNodeWithText(s(R.string.action_delete)).assertIsNotEnabled()
    }

    @Test
    fun typingDeleteEnablesConfirmWhichForwardsTheClick() {
        showDeleteDialog()

        compose.onNode(hasSetTextAction()).performTextInput("DELETE")
        compose.onNodeWithText(s(R.string.action_delete)).assertIsEnabled().performClick()

        assertEquals(listOf("confirm"), events)
    }

    @Test
    fun cancelDismissesWithoutConfirming() {
        showDeleteDialog()

        compose.onNodeWithText(s(R.string.action_cancel)).performClick()

        assertEquals(listOf("dismiss"), events)
    }

    @Test
    fun renamingAnAccountUpdatesItsLabel() {
        val provider = object : QuotaProvider {
            override val service = AiService.ZAI
            override val form = CredentialForm(
                fields = listOf(CredentialField("apiKey", R.string.field_api_key)),
                assemble = { values, _ -> values["apiKey"]?.let { Credential.ApiKeyCredential(it) } }
            )
            override fun accounts(): List<ServiceAccount> = emptyList()
            override suspend fun fetchQuota(account: ServiceAccount): Result<QuotaInfo, AppError> =
                error("unused")
        }
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                ProviderAccountsSection(
                    provider = provider,
                    accounts = listOf(AccountUi("a1", label = "Old")),
                    optionValue = { false },
                    onAddAccount = {},
                    onRemoveAccount = {},
                    onFieldChange = { id, key, value -> events += "field:$id:$key=$value" },
                    onValidate = {},
                    onOptionChange = { _, _ -> },
                    onSignIn = {}
                )
            }
        }
        compose.onNodeWithText("Z.ai").performClick()

        compose.onNodeWithContentDescription(s(R.string.cd_rename_account), substring = true).performClick()
        compose.onNodeWithText(s(R.string.rename_account_title)).assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Old")).performTextReplacement("New")
        compose.onNode(hasClickAction() and hasText(s(R.string.action_rename))).performClick()

        assertEquals(listOf("field:a1:label=New"), events)
    }
}
