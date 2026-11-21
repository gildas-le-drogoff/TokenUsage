package fr.gildasledrogoff.tokenusage.feature.settings.refresh

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.R
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
class RefreshSectionsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = context.getString(id)

    private val intervals = mutableListOf<Long>()
    private val events = mutableListOf<String>()

    private fun showInterval(minutes: Long, exempt: Boolean) {
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                RefreshIntervalSection(
                    currentMinutes = minutes,
                    onIntervalChange = { intervals += it },
                    batteryExempt = exempt,
                    onRequestExemption = { events += "exempt" },
                    onOpenBatterySettings = { events += "settings" }
                )
            }
        }
    }


    @Test
    fun `the current interval is the selected chip`() {
        showInterval(60, exempt = true)

        compose.onNodeWithText(s(R.string.refresh_1h)).assertIsSelected()
        compose.onNodeWithText(s(R.string.refresh_15m)).assertIsNotSelected()
        compose.onNodeWithText(s(R.string.refresh_30m)).assertIsNotSelected()
        compose.onNodeWithText(s(R.string.refresh_manual)).assertIsNotSelected()
    }

    @Test
    fun `manual only is selected for zero minutes`() {
        showInterval(0, exempt = true)

        compose.onNodeWithText(s(R.string.refresh_manual)).assertIsSelected()
    }

    @Test
    fun `an unknown interval falls back to the 30 minutes chip`() {
        showInterval(45, exempt = true)

        compose.onNodeWithText(s(R.string.refresh_30m)).assertIsSelected()
    }

    @Test
    fun `each chip reports its interval`() {
        showInterval(30, exempt = true)

        compose.onNodeWithText(s(R.string.refresh_15m)).performClick()
        compose.onNodeWithText(s(R.string.refresh_1h)).performClick()
        compose.onNodeWithText(s(R.string.refresh_manual)).performClick()

        assertEquals(listOf(15L, 60L, 0L), intervals)
    }


    @Test
    fun `exempt app shows the exempt status and hint without warning or exemption button`() {
        showInterval(30, exempt = true)

        compose.onNodeWithText(s(R.string.battery_status_exempt)).assertExists()
        compose.onNodeWithText(s(R.string.battery_hint_exempt)).assertExists()
        compose.onNodeWithText(s(R.string.battery_warning_optimized)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.battery_action_exempt)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.battery_action_system_settings)).assertExists()
    }

    @Test
    fun `optimized app with periodic refresh shows a warning and the exemption button`() {
        showInterval(30, exempt = false)

        compose.onNodeWithText(s(R.string.battery_status_optimized)).assertExists()
        compose.onNodeWithText(s(R.string.battery_warning_optimized)).assertExists()
        compose.onNodeWithText(s(R.string.battery_hint_manual)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.battery_hint_exempt)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.battery_action_exempt)).assertExists()
    }

    @Test
    fun `optimized app in manual mode shows the manual hint instead of the warning`() {
        showInterval(0, exempt = false)

        compose.onNodeWithText(s(R.string.battery_hint_manual)).assertExists()
        compose.onNodeWithText(s(R.string.battery_warning_optimized)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.battery_action_exempt)).assertExists()
    }

    @Test
    fun `exempt app in manual mode shows the manual hint`() {
        showInterval(0, exempt = true)

        compose.onNodeWithText(s(R.string.battery_hint_manual)).assertExists()
        compose.onNodeWithText(s(R.string.battery_hint_exempt)).assertDoesNotExist()
    }

    @Test
    fun `battery buttons forward their clicks`() {
        showInterval(30, exempt = false)

        compose.onNodeWithText(s(R.string.battery_action_exempt)).performClick()
        compose.onNodeWithText(s(R.string.battery_action_system_settings)).performClick()

        assertEquals(listOf("exempt", "settings"), events)
    }


    private val toggles = mutableListOf<Boolean>()

    private fun showNotifications(enabled: Boolean) {
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                NotificationsSection(enabled = enabled, onToggle = { toggles += it })
            }
        }
    }

    @Test
    fun `notifications section shows its texts and state`() {
        showNotifications(enabled = true)

        compose.onNodeWithText(s(R.string.notifications_title)).assertExists()
        compose.onNodeWithText(s(R.string.notifications_subtitle)).assertExists()
        compose.onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun `notifications switch reports the new state`() {
        showNotifications(enabled = false)

        compose.onNode(isToggleable()).assertIsOff().performClick()

        assertEquals(listOf(true), toggles)
    }
}
