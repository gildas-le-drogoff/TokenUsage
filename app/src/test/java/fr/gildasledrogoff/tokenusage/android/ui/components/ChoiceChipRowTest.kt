package fr.gildasledrogoff.tokenusage.ui.components

import android.app.Application
import android.os.Build
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
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
class ChoiceChipRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val selections = mutableListOf<Int>()

    private fun show(options: List<Int>, selected: Int) {
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                ChoiceChipRow(
                    options = options,
                    selected = selected,
                    label = { "Option $it" },
                    onSelect = { selections += it }
                )
            }
        }
    }

    @Test
    fun `every option is rendered with its label`() {
        show(listOf(1, 2, 3), selected = 2)

        listOf(1, 2, 3).forEach { compose.onNodeWithText("Option $it").assertExists() }
    }

    @Test
    fun `only the selected option is marked selected`() {
        show(listOf(1, 2, 3), selected = 2)

        compose.onNodeWithText("Option 2").assertIsSelected()
        compose.onNodeWithText("Option 1").assertIsNotSelected()
        compose.onNodeWithText("Option 3").assertIsNotSelected()
    }

    @Test
    fun `no option is selected when the value is not part of the list`() {
        show(listOf(1, 2), selected = 9)

        compose.onNodeWithText("Option 1").assertIsNotSelected()
        compose.onNodeWithText("Option 2").assertIsNotSelected()
    }

    @Test
    fun `clicking an option reports it`() {
        show(listOf(1, 2, 3), selected = 1)

        compose.onNodeWithText("Option 3").performClick()

        assertEquals(listOf(3), selections)
    }

    @Test
    fun `clicking the selected option reports it again`() {
        show(listOf(1, 2), selected = 1)

        compose.onNodeWithText("Option 1").performClick()

        assertEquals(listOf(1), selections)
    }

    @Test
    fun `an empty list renders nothing`() {
        show(emptyList(), selected = 1)

        compose.onNodeWithText("Option 1").assertDoesNotExist()
    }
}
