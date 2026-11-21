package fr.gildasledrogoff.tokenusage.core.widget

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
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
class WidgetPrefsManagerTest {
    private lateinit var context: Context
    private lateinit var manager: WidgetPrefsManager

    private val rawPrefs
        get() = context.getSharedPreferences("tokenusage_widget_prefs", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = WidgetPrefsManager(context)
    }

    @Test
    fun `selected services round trip per widget`() {
        manager.saveSelectedServices(1, setOf(AiService.CLAUDE, AiService.CODEX))
        manager.saveSelectedServices(2, setOf(AiService.GEMINI))

        assertEquals(setOf(AiService.CLAUDE, AiService.CODEX), manager.getSelectedServices(1))
        assertEquals(setOf(AiService.GEMINI), manager.getSelectedServices(2))
    }

    @Test
    fun `an unconfigured widget has no selected service`() {
        assertEquals(emptySet<AiService>(), manager.getSelectedServices(42))
    }

    @Test
    fun `unknown service names are ignored`() {
        rawPrefs.edit().putStringSet("widget_3_services", setOf("CLAUDE", "REMOVED_PROVIDER")).commit()

        assertEquals(setOf(AiService.CLAUDE), manager.getSelectedServices(3))
    }

    @Test
    fun `deleting a widget config only removes its own keys`() {
        manager.saveSelectedServices(1, setOf(AiService.CLAUDE))
        manager.saveSelectedServices(11, setOf(AiService.CODEX))

        manager.deleteWidgetConfig(1)

        assertEquals(emptySet<AiService>(), manager.getSelectedServices(1))
        assertEquals(setOf(AiService.CODEX), manager.getSelectedServices(11))
    }

    @Test
    fun `removing a service everywhere updates selections and drops its cache`() {
        manager.saveSelectedServices(1, setOf(AiService.CLAUDE, AiService.CODEX))
        manager.saveSelectedServices(2, setOf(AiService.CODEX))
        manager.cacheAccounts(AiService.CLAUDE, listOf(CachedAccount(tier = "Pro")))
        manager.cacheAccounts(AiService.CODEX, listOf(CachedAccount(tier = "Plus")))

        val changed = manager.removeServiceEverywhere(AiService.CLAUDE)

        assertTrue(changed)
        assertEquals(setOf(AiService.CODEX), manager.getSelectedServices(1))
        assertEquals(setOf(AiService.CODEX), manager.getSelectedServices(2))
        assertEquals(emptyList<CachedAccount>(), manager.getCachedAccounts(AiService.CLAUDE))
        assertEquals("Plus", manager.getCachedAccounts(AiService.CODEX).single().tier)
    }

    @Test
    fun `removing a service no widget shows reports no selection change`() {
        manager.saveSelectedServices(1, setOf(AiService.CODEX))
        manager.cacheAccounts(AiService.GEMINI, listOf(CachedAccount(tier = "Free")))

        assertFalse(manager.removeServiceEverywhere(AiService.GEMINI))
        assertEquals(emptyList<CachedAccount>(), manager.getCachedAccounts(AiService.GEMINI))
    }

    @Test
    fun `a malformed widget key is skipped when removing a service`() {
        rawPrefs.edit().putStringSet("widget_abc_services", setOf("CLAUDE")).commit()

        assertFalse(manager.removeServiceEverywhere(AiService.CLAUDE))
    }

    @Test
    fun `cached accounts round trip with their update timestamp`() {
        val accounts = listOf(
            CachedAccount(
                accountLabel = "Work",
                accountNumber = 1,
                tier = "Max",
                windows = listOf(CachedWindow("5-Hour", 0.4f, 1_700_000_000L)),
                balanceText = "Credit: 5.00 USD",
                dataFetchedAtEpochMillis = 123L,
                status = WidgetDataStatus.OFFLINE
            ),
            CachedAccount(accountNumber = 2)
        )
        val before = System.currentTimeMillis()

        manager.cacheAccounts(AiService.CLAUDE, accounts)

        assertEquals(accounts, manager.getCachedAccounts(AiService.CLAUDE))
        assertTrue(manager.getCachedUpdatedAt(AiService.CLAUDE) >= before)
    }

    @Test
    fun `caching accounts replaces the legacy keys of the service`() {
        rawPrefs.edit().putString("cache_CODEX_tier", "Legacy").commit()

        manager.cacheAccounts(AiService.CODEX, listOf(CachedAccount(tier = "New")))

        assertFalse(rawPrefs.contains("cache_CODEX_tier"))
        assertEquals("New", manager.getCachedAccounts(AiService.CODEX).single().tier)
    }

    @Test
    fun `update timestamp defaults to zero`() {
        assertEquals(0L, manager.getCachedUpdatedAt(AiService.ZAI))
    }

    @Test
    fun `corrupted accounts json yields an empty list`() {
        rawPrefs.edit().putString("cache_CLAUDE_accounts", "{not json").commit()

        assertEquals(emptyList<CachedAccount>(), manager.getCachedAccounts(AiService.CLAUDE))
    }

    @Test
    fun `no cache at all yields an empty list`() {
        assertEquals(emptyList<CachedAccount>(), manager.getCachedAccounts(AiService.DEEPSEEK))
    }

    @Test
    fun `legacy single account cache is migrated on read`() {
        rawPrefs.edit()
            .putString("cache_CLAUDE_labels", "5-Hour,,7-Day")
            .putString("cache_CLAUDE_tier", "Pro")
            .putFloat("cache_CLAUDE_5-Hour_util", 0.25f)
            .putLong("cache_CLAUDE_5-Hour_resets", 1_700_000_000L)
            .putFloat("cache_CLAUDE_7-Day_util", 0.5f)
            .commit()

        val account = manager.getCachedAccounts(AiService.CLAUDE).single()

        assertEquals("Pro", account.tier)
        assertEquals(
            listOf(
                CachedWindow("5-Hour", 0.25f, 1_700_000_000L),
                CachedWindow("7-Day", 0.5f, null)
            ),
            account.windows
        )
    }

    @Test
    fun `legacy cache with only a balance text is still read`() {
        rawPrefs.edit().putString("cache_DEEPSEEK_balance_text", "Balance: 3 CNY").commit()

        val account = manager.getCachedAccounts(AiService.DEEPSEEK).single()

        assertEquals("Balance: 3 CNY", account.balanceText)
        assertEquals(emptyList<CachedWindow>(), account.windows)
    }
}
