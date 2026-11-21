package fr.gildasledrogoff.tokenusage.feature.settings.security

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
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
class DangerZoneTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = context.getString(id)
    private val events = mutableListOf<String>()

    private fun showZone() {
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                DangerZoneSection(onDeleteAll = { events += "deleteAll" })
            }
        }
    }

    private fun showDialog() {
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                DeleteConfirmDialog(onConfirm = { events += "confirm" }, onDismiss = { events += "dismiss" })
            }
        }
    }

    @Test
    fun `danger zone shows its title and button`() {
        showZone()

        compose.onNodeWithText(s(R.string.danger_zone_title)).assertExists()
        compose.onNodeWithText(s(R.string.action_delete_all)).assertExists()
    }

    @Test
    fun `danger zone button forwards the click`() {
        showZone()

        compose.onNodeWithText(s(R.string.action_delete_all)).performClick()

        assertEquals(listOf("deleteAll"), events)
    }

    @Ignore("Robolectric : AlertDialog + TextField ne redevient jamais idle (voir TODO.md)")
    @Test
    fun `the dialog explains what will be deleted`() {
        showDialog()

        compose.onNodeWithText(s(R.string.action_delete_all)).assertExists()
        compose.onNodeWithText(s(R.string.delete_dialog_text)).assertExists()
        compose.onNodeWithText(s(R.string.field_type_delete)).assertExists()
    }

    @Ignore("Robolectric : AlertDialog + TextField ne redevient jamais idle (voir TODO.md)")
    @Test
    fun `confirm is disabled until DELETE is typed`() {
        showDialog()

        compose.onNodeWithText(s(R.string.action_delete)).assertIsNotEnabled()
    }

    @Ignore("Robolectric : AlertDialog + TextField ne redevient jamais idle (voir TODO.md)")
    @Test
    fun `a wrong confirmation word keeps confirm disabled`() {
        showDialog()

        compose.onNode(hasSetTextAction()).performTextInput("delete")

        compose.onNodeWithText(s(R.string.action_delete)).assertIsNotEnabled()
    }

    @Ignore("Robolectric : AlertDialog + TextField ne redevient jamais idle (voir TODO.md)")
    @Test
    fun `typing DELETE enables confirm which forwards the click`() {
        showDialog()

        compose.onNode(hasSetTextAction()).performTextInput("DELETE")
        compose.onNodeWithText(s(R.string.action_delete)).assertIsEnabled().performClick()

        assertEquals(listOf("confirm"), events)
    }

    @Ignore("Robolectric : AlertDialog + TextField ne redevient jamais idle (voir TODO.md)")
    @Test
    fun `cancel dismisses without confirming`() {
        showDialog()

        compose.onNodeWithText(s(R.string.action_cancel)).performClick()

        assertEquals(listOf("dismiss"), events)
    }
}
