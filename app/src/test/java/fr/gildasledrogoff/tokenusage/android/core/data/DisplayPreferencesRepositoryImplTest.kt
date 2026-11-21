package fr.gildasledrogoff.tokenusage.core.data

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.core.domain.model.InsightVisibility
import fr.gildasledrogoff.tokenusage.core.domain.model.LockScreenVisibility
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class DisplayPreferencesRepositoryImplTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun repository() = DisplayPreferencesRepositoryImpl(context)

    @Test
    fun `defaults apply when nothing is stored`() {
        val repo = repository()

        assertEquals(ThemeMode.SYSTEM, repo.themeMode.value)
        assertEquals(QuotaDisplayMode.REMAINING, repo.quotaDisplayMode.value)
        assertEquals(LockScreenVisibility.SYSTEM, repo.lockScreenVisibility.value)
        assertEquals(InsightVisibility(), repo.insightVisibility.value)
        assertFalse(repo.batteryPromptAnswered)
    }

    @Test
    fun `setters update the flows immediately`() {
        val repo = repository()

        repo.setThemeMode(ThemeMode.DARK)
        repo.setQuotaDisplayMode(QuotaDisplayMode.USED)
        repo.setLockScreenVisibility(LockScreenVisibility.HIDDEN)
        repo.setInsightVisibility(InsightVisibility(setOf("pace:CLAUDE")))

        assertEquals(ThemeMode.DARK, repo.themeMode.value)
        assertEquals(QuotaDisplayMode.USED, repo.quotaDisplayMode.value)
        assertEquals(LockScreenVisibility.HIDDEN, repo.lockScreenVisibility.value)
        assertEquals(setOf("pace:CLAUDE"), repo.insightVisibility.value.hiddenKeys)
    }

    @Test
    fun `values persist across instances`() {
        repository().apply {
            setThemeMode(ThemeMode.LIGHT)
            setQuotaDisplayMode(QuotaDisplayMode.USED)
            setLockScreenVisibility(LockScreenVisibility.SHOW_ALL)
            setInsightVisibility(InsightVisibility(setOf("a", "b")))
            markBatteryPromptAnswered()
        }

        val reloaded = repository()

        assertEquals(ThemeMode.LIGHT, reloaded.themeMode.value)
        assertEquals(QuotaDisplayMode.USED, reloaded.quotaDisplayMode.value)
        assertEquals(LockScreenVisibility.SHOW_ALL, reloaded.lockScreenVisibility.value)
        assertEquals(setOf("a", "b"), reloaded.insightVisibility.value.hiddenKeys)
        assertTrue(reloaded.batteryPromptAnswered)
    }

    @Test
    fun `static readers see the stored values`() {
        repository().apply {
            setThemeMode(ThemeMode.DARK)
            setQuotaDisplayMode(QuotaDisplayMode.USED)
        }

        assertEquals(ThemeMode.DARK, DisplayPreferencesRepositoryImpl.themeMode(context))
        assertEquals(QuotaDisplayMode.USED, DisplayPreferencesRepositoryImpl.quotaDisplayMode(context))
    }

    @Test
    fun `unknown stored names fall back to defaults`() {
        context.getSharedPreferences("tokenusage_display_prefs", Context.MODE_PRIVATE).edit()
            .putString("theme_mode", "NEON")
            .putString("quota_display_mode", "BOTH")
            .putString("lock_screen_visibility", "MAYBE")
            .commit()

        val repo = repository()

        assertEquals(ThemeMode.SYSTEM, repo.themeMode.value)
        assertEquals(QuotaDisplayMode.REMAINING, repo.quotaDisplayMode.value)
        assertEquals(LockScreenVisibility.SYSTEM, repo.lockScreenVisibility.value)
    }
}
