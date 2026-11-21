package fr.gildasledrogoff.tokenusage.ui.theme

import android.app.Application
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
class TokenUsageThemeTest {
    @get:Rule
    val compose = createComposeRule()

    private class Observed {
        var statusColors: StatusColors? = null
        var widthClass: WidthClass? = null
        var displayMode: QuotaDisplayMode? = null
        var primary: androidx.compose.ui.graphics.Color? = null
        var criticalMain: androidx.compose.ui.graphics.Color? = null
    }

    @Composable
    private fun Probe(observed: Observed) {
        observed.statusColors = LocalStatusColors.current
        observed.widthClass = MaterialTheme.dimens.widthClass
        observed.displayMode = LocalQuotaDisplayMode.current
        observed.primary = MaterialTheme.colorScheme.primary
        observed.criticalMain = QuotaStatus.CRITICAL.colors.main
    }

    private fun observe(
        mode: ThemeMode,
        display: QuotaDisplayMode = QuotaDisplayMode.REMAINING,
        dynamic: Boolean = false
    ): Observed {
        val observed = Observed()
        compose.setContent {
            TokenUsageTheme(themeMode = mode, quotaDisplayMode = display, dynamicColor = dynamic) {
                Probe(observed)
            }
        }
        compose.waitForIdle()
        return observed
    }

    @Test
    fun `light mode provides the light status colors and static scheme`() {
        val o = observe(ThemeMode.LIGHT)

        assertEquals(LightStatusColors, o.statusColors)
        assertEquals(LightStatusColors.critical.main, o.criticalMain)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF6650A4), o.primary)
    }

    @Test
    fun `dark mode provides the dark status colors and static scheme`() {
        val o = observe(ThemeMode.DARK)

        assertEquals(DarkStatusColors, o.statusColors)
        assertEquals(DarkStatusColors.critical.main, o.criticalMain)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFD0BCFF), o.primary)
    }

    @Test
    @Config(qualifiers = "en-w411dp-h891dp-night")
    fun `system mode follows a dark device`() {
        assertEquals(DarkStatusColors, observe(ThemeMode.SYSTEM).statusColors)
    }

    @Test
    @Config(qualifiers = "en-w411dp-h891dp-notnight")
    fun `system mode follows a light device`() {
        assertEquals(LightStatusColors, observe(ThemeMode.SYSTEM).statusColors)
    }

    @Test
    fun `quota display mode REMAINING is exposed to the content`() {
        assertEquals(
            QuotaDisplayMode.REMAINING,
            observe(ThemeMode.LIGHT, QuotaDisplayMode.REMAINING).displayMode
        )
    }

    @Test
    fun `the last quota display mode is exposed to the content`() {
        assertEquals(
            QuotaDisplayMode.entries.last(),
            observe(ThemeMode.LIGHT, QuotaDisplayMode.entries.last()).displayMode
        )
    }

    @Test
    fun `dynamic color replaces the static scheme on recent Android`() {
        val dynamic = observe(ThemeMode.LIGHT, dynamic = true)

        assertEquals(LightStatusColors, dynamic.statusColors)
        assertNotEquals(androidx.compose.ui.graphics.Color(0xFF6650A4), dynamic.primary)
    }

    @Test
    fun `compact width gives the compact dimens`() {
        assertEquals(WidthClass.COMPACT, observe(ThemeMode.LIGHT).widthClass)
    }

    @Test
    @Config(qualifiers = "en-w700dp-h891dp")
    fun `medium width gives the medium dimens`() {
        assertEquals(WidthClass.MEDIUM, observe(ThemeMode.LIGHT).widthClass)
    }

    @Test
    @Config(qualifiers = "en-w900dp-h891dp")
    fun `expanded width gives the expanded dimens`() {
        assertEquals(WidthClass.EXPANDED, observe(ThemeMode.LIGHT).widthClass)
    }

    @Test
    fun `status colors read from the composition local`() {
        var main: androidx.compose.ui.graphics.Color? = null
        compose.setContent {
            CompositionLocalProvider(LocalStatusColors provides DarkStatusColors) {
                main = QuotaStatus.LOW.colors.main
            }
        }
        compose.waitForIdle()

        assertEquals(DarkStatusColors.low.main, main)
    }
}
