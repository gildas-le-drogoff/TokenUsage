package fr.gildasledrogoff.tokenusage.feature.settings.common

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
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
class SettingsSubScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var backs = 0

    private fun show() {
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                SettingsSubScreen(title = "Sub title", onNavigateBack = { backs++ }) {
                    Text("Body text")
                    Box(Modifier.fillMaxWidth().height(10.dp).testTag("body"))
                }
            }
        }
    }

    @Test
    fun `title and content are displayed`() {
        show()

        compose.onNodeWithText("Sub title").assertExists()
        compose.onNodeWithText("Body text").assertExists()
    }

    @Test
    fun `back button invokes the callback`() {
        show()

        compose.onNodeWithContentDescription(context.getString(R.string.cd_back)).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `compact screens use the full width`() {
        show()

        val width = compose.onNodeWithTag("body").getUnclippedBoundsInRoot().width
        assertTrue("width=$width", width > 340.dp)
    }

    @Test
    @Config(qualifiers = "en-w1000dp-h891dp")
    fun `wide screens cap the content width`() {
        show()

        val width = compose.onNodeWithTag("body").getUnclippedBoundsInRoot().width
        assertTrue("width=$width", width <= SettingsContentMaxWidth)
    }
}
