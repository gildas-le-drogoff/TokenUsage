package fr.gildasledrogoff.tokenusage.ui.security

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.core.security.IntegrityThreat
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
class CompromisedDeviceScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = context.getString(id)
    private val events = mutableListOf<String>()

    private fun show(threats: Set<IntegrityThreat>, acceptable: Boolean) {
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                CompromisedDeviceScreen(
                    threats = threats,
                    onClose = { events += "close" },
                    onAcceptRisk = if (acceptable) ({ events += "accept" }) else null
                )
            }
        }
    }

    @Test
    fun `title message and close button are always shown`() {
        show(emptySet(), acceptable = false)

        compose.onNodeWithText(s(R.string.integrity_title)).assertExists()
        compose.onNodeWithText(s(R.string.integrity_message)).assertExists()
        compose.onNodeWithText(s(R.string.integrity_close)).assertExists()
    }

    @Test
    fun `without threats the threat list is hidden`() {
        show(emptySet(), acceptable = false)

        compose.onNodeWithText(s(R.string.integrity_threats_header), substring = true).assertDoesNotExist()
    }

    @Test
    fun `each detected threat is listed under the header`() {
        show(IntegrityThreat.entries.toSet(), acceptable = false)

        compose.onNodeWithText(s(R.string.integrity_threats_header), substring = true).assertExists()
        listOf(
            R.string.integrity_threat_root,
            R.string.integrity_threat_hooking,
            R.string.integrity_threat_debugger,
            R.string.integrity_threat_tampered
        ).forEach { compose.onNodeWithText(s(it), substring = true).assertExists() }
    }

    @Test
    fun `only the detected threats are listed`() {
        show(setOf(IntegrityThreat.ROOT), acceptable = false)

        compose.onNodeWithText(s(R.string.integrity_threat_root), substring = true).assertExists()
        compose.onNodeWithText(s(R.string.integrity_threat_debugger), substring = true).assertDoesNotExist()
    }

    @Test
    fun `a blocking screen explains how to reopen the app and offers no override`() {
        show(setOf(IntegrityThreat.ROOT), acceptable = false)

        compose.onNodeWithText(s(R.string.integrity_open_app)).assertExists()
        compose.onNodeWithText(s(R.string.integrity_accept_risk)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.integrity_accept_risk_note)).assertDoesNotExist()
    }

    @Test
    fun `an overridable screen offers to accept the risk`() {
        show(setOf(IntegrityThreat.ROOT), acceptable = true)

        compose.onNodeWithText(s(R.string.integrity_open_app)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.integrity_accept_risk)).assertExists()
        compose.onNodeWithText(s(R.string.integrity_accept_risk_note)).assertExists()
    }

    @Test
    fun `close button triggers onClose only`() {
        show(setOf(IntegrityThreat.ROOT), acceptable = true)

        compose.onNodeWithText(s(R.string.integrity_close)).performScrollTo().performClick()

        assertEquals(listOf("close"), events)
    }

    @Test
    fun `accept button triggers onAcceptRisk only`() {
        show(setOf(IntegrityThreat.ROOT), acceptable = true)

        compose.onNodeWithText(s(R.string.integrity_accept_risk)).performScrollTo().performClick()

        assertEquals(listOf("accept"), events)
    }

    @Test
    @Config(qualifiers = "en-w900dp-h891dp")
    fun `the expanded layout renders the same content`() {
        show(setOf(IntegrityThreat.DEBUGGER), acceptable = true)

        compose.onNodeWithText(s(R.string.integrity_title)).assertExists()
        compose.onNodeWithText(s(R.string.integrity_threat_debugger), substring = true).assertExists()
        compose.onNodeWithText(s(R.string.integrity_accept_risk)).assertExists()
    }
}
