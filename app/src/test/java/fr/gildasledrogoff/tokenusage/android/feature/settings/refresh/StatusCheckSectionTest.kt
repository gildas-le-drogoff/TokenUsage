package fr.gildasledrogoff.tokenusage.feature.settings.refresh

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.core.status.StatusCheckSettings
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
class StatusCheckSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = context.getString(id)

    private val all = mutableListOf<Boolean>()
    private val providers = mutableListOf<Pair<AiService, Boolean>>()
    private val supported = StatusCheckSettings.supportedServices

    private fun show(settings: StatusCheckSettings) {
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                StatusCheckSection(
                    settings = settings,
                    onToggleAll = { all += it },
                    onToggleProvider = { service, enabled -> providers += service to enabled }
                )
            }
        }
    }

    private fun switches() = compose.onAllNodes(isToggleable())

    @Test
    fun `texts and one switch per supported provider plus the global one`() {
        show(StatusCheckSettings())

        compose.onNodeWithText(s(R.string.status_check_title)).assertExists()
        compose.onNodeWithText(s(R.string.status_check_desc)).assertExists()
        compose.onNodeWithText(s(R.string.status_check_all)).assertExists()
        supported.forEach { compose.onNodeWithText(it.displayName).assertExists() }
        switches().assertCountEquals(supported.size + 1)
    }

    @Test
    fun `everything is off by default and provider switches are enabled`() {
        show(StatusCheckSettings())

        (0..supported.size).forEach { switches()[it].assertIsOff() }
        (1..supported.size).forEach { switches()[it].assertIsEnabled() }
    }

    @Test
    fun `the global switch turns every provider on and locks them`() {
        show(StatusCheckSettings(allProviders = true))

        switches()[0].assertIsOn().assertIsEnabled()
        (1..supported.size).forEach { switches()[it].assertIsOn().assertIsNotEnabled() }
    }

    @Test
    fun `a single provider can be on`() {
        val enabled = supported.first()
        show(StatusCheckSettings(providers = setOf(enabled)))

        switches()[0].assertIsOff()
        switches()[1].assertIsOn()
        if (supported.size > 1) switches()[2].assertIsOff()
    }

    @Test
    fun `global switch reports its new state`() {
        show(StatusCheckSettings())

        switches()[0].performClick()

        assertEquals(listOf(true), all)
        assertTrue(providers.isEmpty())
    }

    @Test
    fun `provider switch reports the service and the new state`() {
        show(StatusCheckSettings(providers = setOf(supported[1])))

        switches()[1].performClick()
        switches()[2].performClick()

        assertEquals(listOf(supported[0] to true, supported[1] to false), providers)
        assertTrue(all.isEmpty())
    }
}
