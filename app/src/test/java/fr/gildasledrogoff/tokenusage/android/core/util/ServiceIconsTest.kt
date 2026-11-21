package fr.gildasledrogoff.tokenusage.core.util

import android.app.Application
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class ServiceIconsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `iconRes exposes the service icon for every service`() {
        AiService.entries.forEach { assertEquals(it.icon, it.iconRes) }
    }

    @Test
    fun `ServiceIcon renders on a light surface`() {
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                ServiceIcon(
                    iconRes = AiService.CLAUDE.iconRes,
                    contentDescription = "light-icon",
                    size = 32.dp
                )
            }
        }

        compose.onNodeWithContentDescription("light-icon").assertIsDisplayed()
    }

    @Test
    fun `ServiceIcon renders inside a badge on a dark surface`() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ServiceIcon(
                    iconRes = AiService.CLAUDE.iconRes,
                    contentDescription = "dark-icon",
                    size = 32.dp
                )
            }
        }

        compose.onNodeWithContentDescription("dark-icon").assertIsDisplayed()
    }
}
