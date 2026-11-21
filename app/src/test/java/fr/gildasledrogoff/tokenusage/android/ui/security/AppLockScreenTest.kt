package fr.gildasledrogoff.tokenusage.ui.security

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.ui.theme.TokenUsageTheme
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
class AppLockScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = context.getString(id)
    private val events = mutableListOf<String>()

    private fun show() {
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                AppLockScreen(onUnlock = { events += "unlock" }, onDisableLock = { events += "disable" })
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the lock screen shows its title and subtitle`() {
        show()

        compose.onNodeWithText(s(R.string.unlock_title)).assertExists()
        compose.onNodeWithText(s(R.string.unlock_subtitle)).assertExists()
    }

    @Test
    fun `without a fragment activity the screen reports that the lock is unavailable`() {
        show()

        compose.onNodeWithText(s(R.string.app_lock_unavailable)).assertExists()
        compose.onNodeWithText(s(R.string.unlock_action)).assertExists()
        compose.onNodeWithText(s(R.string.app_lock_no_authenticator)).assertDoesNotExist()
    }

    @Test
    fun `retrying keeps the error and never unlocks`() {
        show()

        compose.onNodeWithText(s(R.string.unlock_action)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(s(R.string.app_lock_unavailable)).assertExists()
        assertTrue(events.isEmpty())
    }
}
