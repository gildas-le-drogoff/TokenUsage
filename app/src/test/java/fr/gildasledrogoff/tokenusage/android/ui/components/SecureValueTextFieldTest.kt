package fr.gildasledrogoff.tokenusage.ui.components

import android.app.Application
import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
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
class SecureValueTextFieldTest {
    @get:Rule
    val compose = createComposeRule()

    private val changes = mutableListOf<String>()
    private var value by mutableStateOf("")

    private fun show(
        enabled: Boolean = true,
        isError: Boolean = false,
        withLabel: Boolean = true,
        withSupporting: Boolean = false
    ) {
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                SecureValueTextField(
                    value = value,
                    onValueChange = { changes += it },
                    enabled = enabled,
                    isError = isError,
                    label = if (withLabel) ({ Text("Secret label") }) else null,
                    supportingText = if (withSupporting) ({ Text("Secret hint") }) else null
                )
            }
        }
    }

    @Test
    fun `label and supporting text are shown when provided`() {
        show(withSupporting = true)

        compose.onNodeWithText("Secret label").assertExists()
        compose.onNodeWithText("Secret hint").assertExists()
    }

    @Test
    fun `no label and no supporting text by default`() {
        show(withLabel = false)

        compose.onNodeWithText("Secret label").assertDoesNotExist()
        compose.onNodeWithText("Secret hint").assertDoesNotExist()
    }

    @Test
    fun `typing reports the new text`() {
        show()

        compose.onNode(hasSetTextAction()).performTextInput("abc")
        compose.waitForIdle()

        assertEquals("abc", changes.last())
    }

    @Test
    fun `an external value change is not echoed back to the caller`() {
        show()

        value = "from-outside"
        compose.waitForIdle()

        assertTrue("no callback expected, got $changes", changes.isEmpty())
    }

    @Test
    fun `typing after an external change appends to the new value`() {
        show()
        value = "abc"
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).performTextInput("d")
        compose.waitForIdle()

        assertEquals("abcd", changes.last())
    }

    @Test
    fun `the field can be disabled`() {
        show(enabled = false)

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertIsNotEnabled()
    }

    @Test
    fun `the field is enabled by default and accepts the error flag`() {
        show(isError = true)

        compose.onNode(hasSetTextAction()).assertIsEnabled()
    }
}
