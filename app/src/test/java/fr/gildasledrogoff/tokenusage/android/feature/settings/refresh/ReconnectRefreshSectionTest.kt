package fr.gildasledrogoff.tokenusage.feature.settings.refresh

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.ui.theme.TokenUsageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class ReconnectRefreshSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = context.getString(id)
    private val changes = mutableListOf<Boolean>()

    private fun show(enabled: Boolean) {
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                ReconnectRefreshSection(enabled = enabled, onEnabledChange = { changes += it })
            }
        }
    }

    @Test
    fun `subtitle depends on the state`() {
        show(enabled = false)
        compose.onNodeWithText(s(R.string.reconnect_refresh_subtitle_off)).assertExists()
        compose.onNodeWithText(s(R.string.reconnect_refresh_subtitle_on)).assertDoesNotExist()
    }

    @Test
    fun `subtitle when enabled`() {
        show(enabled = true)
        compose.onNodeWithText(s(R.string.reconnect_refresh_subtitle_on)).assertExists()
        compose.onNodeWithText(s(R.string.reconnect_refresh_subtitle_off)).assertDoesNotExist()
    }

    @Test
    fun `turning on first asks for consent and changes nothing`() {
        show(enabled = false)

        compose.onNode(isToggleable()).performClick()

        compose.onNodeWithText(s(R.string.reconnect_refresh_dialog_title)).assertExists()
        compose.onNodeWithText(s(R.string.reconnect_refresh_dialog_text)).assertExists()
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `accepting the consent enables the option and closes the dialog`() {
        show(enabled = false)
        compose.onNode(isToggleable()).performClick()

        compose.onNodeWithText(s(R.string.reconnect_refresh_dialog_accept)).performClick()

        assertEquals(listOf(true), changes)
        compose.onNodeWithText(s(R.string.reconnect_refresh_dialog_title)).assertDoesNotExist()
    }

    @Test
    fun `declining the consent leaves the option untouched`() {
        show(enabled = false)
        compose.onNode(isToggleable()).performClick()

        compose.onNodeWithText(s(R.string.reconnect_refresh_dialog_decline)).performClick()

        assertTrue(changes.isEmpty())
        compose.onNodeWithText(s(R.string.reconnect_refresh_dialog_title)).assertDoesNotExist()
    }

    @Test
    fun `turning off needs no consent`() {
        show(enabled = true)

        compose.onNode(isToggleable()).performClick()

        assertEquals(listOf(false), changes)
        compose.onNodeWithText(s(R.string.reconnect_refresh_dialog_title)).assertDoesNotExist()
    }
}
