package fr.gildasledrogoff.tokenusage.feature.settings

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = context.getString(id)

    private val navigated = mutableListOf<SettingsDestination>()
    private var credits = 0
    private var backs = 0

    private fun show(showDiagnostics: Boolean = false) {
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                SettingsScreen(
                    onNavigateBack = { backs++ },
                    onNavigateTo = { navigated += it },
                    onNavigateToCredits = { credits++ },
                    showDiagnostics = showDiagnostics
                )
            }
        }
    }

    private val visibleByDefault = SettingsDestination.entries - SettingsDestination.DIAGNOSTICS

    @Test
    fun `destination routes are unique and prefixed`() {
        val routes = SettingsDestination.entries.map { it.route }

        assertEquals(routes.size, routes.toSet().size)
        assertTrue(routes.all { it.startsWith("settings/") || it == "diagnostics" })
    }

    @Test
    fun `every regular destination is listed`() {
        show()

        visibleByDefault.forEach { compose.onNodeWithText(s(it.title)).assertExists() }
        compose.onNodeWithText(s(R.string.settings_credits_title)).assertExists()
    }

    @Test
    fun `diagnostics is hidden unless requested`() {
        show(showDiagnostics = false)

        compose.onNodeWithText(s(SettingsDestination.DIAGNOSTICS.title)).assertDoesNotExist()
    }

    @Test
    fun `diagnostics is listed when requested`() {
        show(showDiagnostics = true)

        compose.onNodeWithText(s(SettingsDestination.DIAGNOSTICS.title)).assertExists()
    }

    @Test
    fun `clicking an entry navigates to its destination`() {
        show(showDiagnostics = true)

        SettingsDestination.entries.forEach {
            compose.onNodeWithText(s(it.title)).performScrollTo().performClick()
        }

        assertEquals(SettingsDestination.entries.toList(), navigated)
    }

    @Test
    fun `credits entry has its own callback`() {
        show()

        compose.onNodeWithText(s(R.string.settings_credits_title)).performScrollTo().performClick()

        assertEquals(1, credits)
        assertTrue(navigated.isEmpty())
    }

    @Test
    fun `back button invokes the callback`() {
        show()

        compose.onNodeWithContentDescription(s(R.string.cd_back)).performClick()

        assertEquals(1, backs)
    }
}
