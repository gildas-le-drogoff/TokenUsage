package fr.gildasledrogoff.tokenusage.feature.settings

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.ui.theme.TokenUsageTheme
import org.junit.Assert.assertEquals
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
class ConfirmDeleteIconButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = context.getString(id)
    private var confirmed = 0

    private fun show() {
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                ConfirmDeleteIconButton(contentDescription = "Remove it", onConfirmed = { confirmed++ })
            }
        }
    }

    @Test
    fun `no dialog until the icon is clicked`() {
        show()

        compose.onNodeWithContentDescription("Remove it").assertExists()
        compose.onNodeWithText(s(R.string.delete_account_warning_title)).assertDoesNotExist()
    }

    @Test
    fun `clicking the icon opens the warning without deleting`() {
        show()

        compose.onNodeWithContentDescription("Remove it").performClick()

        compose.onNodeWithText(s(R.string.delete_account_warning_title)).assertExists()
        assertEquals(0, confirmed)
    }

    @Test
    fun `confirming deletes once and closes the dialog`() {
        show()
        compose.onNodeWithContentDescription("Remove it").performClick()

        compose.onNodeWithText(s(R.string.action_delete)).performClick()

        assertEquals(1, confirmed)
        compose.onNodeWithText(s(R.string.delete_account_warning_title)).assertDoesNotExist()
    }

    @Test
    fun `cancelling closes the dialog without deleting`() {
        show()
        compose.onNodeWithContentDescription("Remove it").performClick()

        compose.onNodeWithText(s(R.string.action_cancel)).performClick()

        assertEquals(0, confirmed)
        compose.onNodeWithText(s(R.string.delete_account_warning_title)).assertDoesNotExist()
    }
}
