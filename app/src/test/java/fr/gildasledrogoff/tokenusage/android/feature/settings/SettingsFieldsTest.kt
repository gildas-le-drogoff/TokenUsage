package fr.gildasledrogoff.tokenusage.feature.settings

import android.app.Application
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.ui.theme.TokenUsageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
class SettingsFieldsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun field(tag: String): @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.height(20.dp).testTag(tag))
    }

    private fun show(count: Int) {
        val fields = (1..count).map { field("f$it") }.toTypedArray()
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                SettingsFields(*fields)
            }
        }
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top
    private fun width(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().width

    @Test
    fun `compact screens stack every field on its own row at full width`() {
        show(3)

        assertNotEquals(top("f1"), top("f2"))
        assertNotEquals(top("f2"), top("f3"))
        assertEquals(width("f1"), width("f2"))
    }

    @Test
    @Config(qualifiers = "en-w700dp-h891dp")
    fun `wider screens pair fields two by two`() {
        show(4)

        assertEquals(top("f1"), top("f2"))
        assertEquals(top("f3"), top("f4"))
        assertNotEquals(top("f1"), top("f3"))
    }

    @Test
    @Config(qualifiers = "en-w700dp-h891dp")
    fun `a lone last field only takes half of the row`() {
        show(3)

        assertEquals(width("f1"), width("f3"))
        assertTrue(width("f3") < 700.dp / 2)
    }

    @Test
    @Config(qualifiers = "en-w700dp-h891dp")
    fun `no fields render nothing`() {
        show(0)

        compose.onNodeWithTag("f1").assertDoesNotExist()
    }
}
