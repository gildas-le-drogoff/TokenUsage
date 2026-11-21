package fr.gildasledrogoff.tokenusage.feature.settings.common

import android.app.Application
import android.os.Build
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class ToggleSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val toggles = mutableListOf<Boolean>()

    private fun show(checked: Boolean) {
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                ToggleSection(
                    title = "Section title",
                    subtitle = "Section subtitle",
                    checked = checked,
                    onToggle = { toggles += it }
                )
            }
        }
    }

    @Test
    fun `title and subtitle are displayed`() {
        show(checked = false)

        compose.onNodeWithText("Section title").assertExists()
        compose.onNodeWithText("Section subtitle").assertExists()
    }

    @Test
    fun `the switch reflects the checked state`() {
        show(checked = true)
        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun `the switch is off when unchecked`() {
        show(checked = false)
        compose.onNode(isToggleable()).assertIsOff()
    }

    @Test
    fun `clicking an unchecked switch asks to enable`() {
        show(checked = false)

        compose.onNode(isToggleable()).performClick()

        assertEquals(listOf(true), toggles)
    }

    @Test
    fun `clicking a checked switch asks to disable`() {
        show(checked = true)

        compose.onNode(isToggleable()).performClick()

        assertEquals(listOf(false), toggles)
    }
}
