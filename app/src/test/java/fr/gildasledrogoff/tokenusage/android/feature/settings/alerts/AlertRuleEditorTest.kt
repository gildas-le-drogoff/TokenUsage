package fr.gildasledrogoff.tokenusage.feature.settings.alerts

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import fr.gildasledrogoff.tokenusage.core.alert.AlertRule
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.ui.theme.TokenUsageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class AlertRuleEditorTest {
    @get:Rule
    val compose = createComposeRule()

    private var current by mutableStateOf(AlertRule())

    private fun show(
        rule: AlertRule = AlertRule(),
        supportsBalance: Boolean = false,
        windows: List<String> = listOf("5-Hour", "7-Day")
    ) {
        current = rule
        compose.setContent {
            TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                AlertRuleEditor(current, supportsBalance, windows, onRuleChange = { current = it })
            }
        }
    }

    private fun switch(label: String): SemanticsNodeInteraction {
        val row = compose.onNode(hasText(label) and !isToggleable()).fetchSemanticsNode().boundsInRoot
        val switches = compose.onAllNodes(isToggleable())
        val index = switches.fetchSemanticsNodes().indexOfFirst {
            it.boundsInRoot.center.y in row.top..row.bottom
        }
        return switches[index]
    }

    @Test
    fun `default rule shows threshold and window filter`() {
        show()

        switch("Alerts enabled").assertIsOn()
        compose.onNodeWithText("Quota threshold: 80%").assertIsDisplayed()
        compose.onNodeWithText("Applies to").assertIsDisplayed()
        compose.onNodeWithText("All windows").assertIsDisplayed()
        switch("Predicted depletion").assertIsOff()
        compose.onNodeWithText("Low balance").assertDoesNotExist()
    }

    @Test
    fun `disabling alerts hides every other option`() {
        show()

        switch("Alerts enabled").performClick()

        assertEquals(false, current.enabled)
        compose.onNodeWithText("Quota threshold: 80%").assertDoesNotExist()
        compose.onNodeWithText("Predicted depletion").assertDoesNotExist()
    }

    @Test
    fun `turning the quota threshold off and on restores the default`() {
        show()

        switch("Quota threshold: 80%").performClick()
        assertNull(current.quotaThreshold)
        compose.onNodeWithText("Applies to").assertDoesNotExist()

        switch("Quota threshold").performClick()
        assertEquals(AlertRule.DEFAULT_QUOTA_THRESHOLD, current.quotaThreshold!!, 1e-9)
    }

    @Test
    fun `a window can be targeted`() {
        show()

        compose.onNodeWithText("7-Day").performClick()

        assertEquals("7-Day", current.quotaWindowLabel)
    }

    @Test
    fun `a single window hides the filter`() {
        show(windows = listOf("5-Hour"))

        compose.onNodeWithText("Applies to").assertDoesNotExist()
    }

    @Test
    fun `the balance threshold uses a default then parses the field`() {
        show(supportsBalance = true)

        switch("Low balance").performClick()
        assertEquals(5.0, current.balanceThreshold!!, 1e-9)

        compose.onNodeWithText("5").performTextReplacement("2,5")
        assertEquals(2.5, current.balanceThreshold!!, 1e-9)

        compose.onNodeWithText("2,5").performTextReplacement("abc")
        assertEquals(2.5, current.balanceThreshold!!, 1e-9)
    }

    @Test
    fun `depletion horizon offers day and hour choices`() {
        show()

        switch("Predicted depletion").performClick()
        assertEquals(24, current.depletionHorizonHours)
        compose.onNodeWithText("6 h").assertIsDisplayed()
        compose.onNodeWithText("7 d").assertIsDisplayed()

        compose.onNodeWithText("2 d").performClick()
        assertEquals(48, current.depletionHorizonHours)
    }

    @Test
    fun `a custom horizon is listed among the choices`() {
        show(rule = AlertRule(depletionHorizonHours = 36))

        compose.onNodeWithText("36 h").assertIsDisplayed()
    }

    @Test
    fun `amount helpers accept commas and refuse negatives`() {
        assertEquals(1.5, parseAmount(" 1,5 ")!!, 1e-9)
        assertNull(parseAmount("-1"))
        assertNull(parseAmount("NaN"))
        assertEquals("3", formatAmount(3.0))
        assertEquals("2.5", formatAmount(2.5))
    }
}
