package fr.gildasledrogoff.tokenusage.core.widget

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    application = Application::class,
    qualifiers = "en"
)
class WidgetThemeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Palette.use(dark = true)
    }

    @After
    fun tearDown() {
        Palette.use(dark = true)
    }

    private fun setThemeMode(mode: ThemeMode) {
        context.applicationContext
            .getSharedPreferences("tokenusage_display_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("theme_mode", mode.name)
            .commit()
    }

    private fun window(
        utilization: Float,
        showUsed: Boolean = true,
        resetsAt: Long? = null
    ) = WindowSnapshot(
        label = "5h",
        utilization = utilization,
        resetsAtEpochSecond = resetsAt,
        showUsed = showUsed
    )

    private fun inFuture(seconds: Long): Long = Instant.now().epochSecond + seconds


    @Test
    fun `Palette use dark exposes the dark palette`() {
        Palette.use(dark = true)
        assertTrue(Palette.isDark)
    }

    @Test
    fun `Palette use light exposes the light palette`() {
        Palette.use(dark = false)
        assertFalse(Palette.isDark)
    }

    @Test
    fun `Palette colors differ between light and dark`() {
        Palette.use(dark = true)
        val darkColors = listOf(
            Palette.base, Palette.title, Palette.secondary, Palette.tertiary,
            Palette.chipBackground, Palette.track, Palette.divider, Palette.iconTint,
            Palette.danger, Palette.warning, Palette.safe
        )
        Palette.use(dark = false)
        val lightColors = listOf(
            Palette.base, Palette.title, Palette.secondary, Palette.tertiary,
            Palette.chipBackground, Palette.track, Palette.divider, Palette.iconTint,
            Palette.danger, Palette.warning, Palette.safe
        )
        darkColors.zip(lightColors).forEach { (d, l) -> assertNotEquals(d, l) }
    }

    @Test
    fun `Palette status colors are distinct from each other`() {
        listOf(true, false).forEach { dark ->
            Palette.use(dark)
            assertEquals(
                3,
                setOf(Palette.danger, Palette.warning, Palette.safe).size
            )
        }
    }


    @Test
    fun `isDarkWidgetTheme is true when theme is DARK`() {
        setThemeMode(ThemeMode.DARK)
        assertTrue(isDarkWidgetTheme(context))
    }

    @Test
    fun `isDarkWidgetTheme is false when theme is LIGHT`() {
        setThemeMode(ThemeMode.LIGHT)
        assertFalse(isDarkWidgetTheme(context))
    }

    @Test
    @Config(qualifiers = "en-night")
    fun `isDarkWidgetTheme follows the system when SYSTEM and night mode`() {
        setThemeMode(ThemeMode.SYSTEM)
        assertTrue(isDarkWidgetTheme(context))
    }

    @Test
    @Config(qualifiers = "en-notnight")
    fun `isDarkWidgetTheme follows the system when SYSTEM and day mode`() {
        setThemeMode(ThemeMode.SYSTEM)
        assertFalse(isDarkWidgetTheme(context))
    }


    @Test
    fun `utilizationColor is safe below 60 percent`() {
        assertEquals(Palette.safe, utilizationColor(0f))
        assertEquals(Palette.safe, utilizationColor(0.599f))
    }

    @Test
    fun `utilizationColor is warning from 60 to below 85 percent`() {
        assertEquals(Palette.warning, utilizationColor(0.60f))
        assertEquals(Palette.warning, utilizationColor(0.849f))
    }

    @Test
    fun `utilizationColor is danger from 85 percent`() {
        assertEquals(Palette.danger, utilizationColor(0.85f))
        assertEquals(Palette.danger, utilizationColor(1f))
        assertEquals(Palette.danger, utilizationColor(1.5f))
    }


    @Test
    fun `gaugeText shows used percentage when showUsed`() {
        assertEquals("42% used", gaugeText(context, window(0.42f, showUsed = true)))
    }

    @Test
    fun `gaugeText shows remaining percentage when not showUsed`() {
        assertEquals("58% left", gaugeText(context, window(0.42f, showUsed = false)))
    }

    @Test
    fun `gaugeText clamps out of range utilization`() {
        assertEquals("100% used", gaugeText(context, window(1.7f, showUsed = true)))
        assertEquals("0% used", gaugeText(context, window(-0.3f, showUsed = true)))
        assertEquals("0% left", gaugeText(context, window(2f, showUsed = false)))
        assertEquals("100% left", gaugeText(context, window(-1f, showUsed = false)))
    }


    @Test
    fun `barProgress equals utilization when showUsed`() {
        assertEquals(0.25f, barProgress(window(0.25f, showUsed = true)), 0.0001f)
    }

    @Test
    fun `barProgress is the remainder when not showUsed`() {
        assertEquals(0.75f, barProgress(window(0.25f, showUsed = false)), 0.0001f)
    }

    @Test
    fun `barProgress clamps out of range values`() {
        assertEquals(1f, barProgress(window(3f, showUsed = true)), 0f)
        assertEquals(0f, barProgress(window(-3f, showUsed = true)), 0f)
        assertEquals(0f, barProgress(window(3f, showUsed = false)), 0f)
        assertEquals(1f, barProgress(window(-3f, showUsed = false)), 0f)
    }


    @Test
    fun `resetCountdownText shows days and hours when at least a day away`() {
        val text = resetCountdownText(context, inFuture(49 * 3600L + 30))
        assertEquals("2d 1h", text)
    }

    @Test
    fun `resetCountdownText shows hours and minutes when under a day`() {
        val text = resetCountdownText(context, inFuture(2 * 3600L + 30 * 60L + 30))
        assertEquals("2h 30m", text)
    }

    @Test
    fun `resetCountdownText shows minutes only when under an hour`() {
        val text = resetCountdownText(context, inFuture(10 * 60L + 30))
        assertEquals("10m", text)
    }

    @Test
    fun `resetCountdownText falls back to waiting message when reset is in the past`() {
        val text = resetCountdownText(context, Instant.now().epochSecond - 600)
        assertEquals("Waiting for data...", text)
    }


    @Test
    fun `resetLine is empty without reset time`() {
        assertEquals("", resetLine(context, window(0.5f, resetsAt = null)))
    }

    @Test
    fun `resetLine is empty when reset is in the past`() {
        assertEquals("", resetLine(context, window(0.5f, resetsAt = Instant.now().epochSecond - 60)))
    }

    @Test
    fun `resetLine includes the countdown when reset is upcoming`() {
        val line = resetLine(context, window(0.5f, resetsAt = inFuture(2 * 3600L + 30 * 60L + 30)))
        assertTrue("Unexpected line: $line", line.contains("2h 30m"))
    }
}
