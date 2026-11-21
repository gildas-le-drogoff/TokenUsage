package fr.gildasledrogoff.tokenusage.core.widget

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.core.data.DisplayPreferencesRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    application = Application::class,
    qualifiers = "en"
)
class WidgetSnapshotsTest {
    private lateinit var context: Context
    private lateinit var prefs: WidgetPrefsManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = WidgetPrefsManager(context)
    }

    private fun snapshot(
        label: String? = null,
        number: Int? = null,
        status: WidgetDataStatus = WidgetDataStatus.FRESH,
        fetchedAt: Instant? = null
    ) = ServiceSnapshot(
        service = AiService.CLAUDE,
        tier = null,
        windows = emptyList(),
        balanceText = null,
        accountLabel = label,
        accountNumber = number,
        status = status,
        dataFetchedAt = fetchedAt
    )

    @Test
    fun `load returns nothing for an unconfigured widget`() {
        assertEquals(emptyList<ServiceSnapshot>(), WidgetSnapshots.load(context, 7))
    }

    @Test
    fun `load maps cached accounts in service order`() {
        prefs.saveSelectedServices(7, setOf(AiService.CODEX, AiService.CLAUDE))
        prefs.cacheAccounts(
            AiService.CLAUDE,
            listOf(
                CachedAccount(
                    accountLabel = "Work",
                    tier = "Max",
                    windows = listOf(CachedWindow("5-Hour", 0.3f, 99L)),
                    balanceText = "5 USD",
                    dataFetchedAtEpochMillis = 1_000L,
                    status = WidgetDataStatus.FAILED
                ),
                CachedAccount(accountNumber = 2)
            )
        )
        prefs.cacheAccounts(AiService.CODEX, listOf(CachedAccount(tier = "Plus")))

        val snapshots = WidgetSnapshots.load(context, 7)

        assertEquals(
            listOf(AiService.CLAUDE, AiService.CLAUDE, AiService.CODEX),
            snapshots.map { it.service }
        )
        val first = snapshots.first()
        assertEquals("Work", first.accountLabel)
        assertEquals("Max", first.tier)
        assertEquals("5 USD", first.balanceText)
        assertEquals(WidgetDataStatus.FAILED, first.status)
        assertEquals(Instant.ofEpochMilli(1_000L), first.dataFetchedAt)
        assertEquals(listOf(WindowSnapshot("5-Hour", 0.3f, 99L, showUsed = false)), first.windows)
        assertEquals(2, snapshots[1].accountNumber)
        assertNull(snapshots[1].dataFetchedAt)
    }

    @Test
    fun `load follows the used display mode`() {
        DisplayPreferencesRepositoryImpl(context).setQuotaDisplayMode(QuotaDisplayMode.USED)
        prefs.saveSelectedServices(1, setOf(AiService.CLAUDE))
        prefs.cacheAccounts(
            AiService.CLAUDE,
            listOf(CachedAccount(windows = listOf(CachedWindow("7-Day", 0.5f))))
        )

        assertTrue(WidgetSnapshots.load(context, 1).single().windows.single().showUsed)
    }

    @Test
    fun `a selected service without cache gets an empty placeholder`() {
        prefs.saveSelectedServices(1, setOf(AiService.GEMINI))

        val snapshot = WidgetSnapshots.load(context, 1).single()

        assertEquals(AiService.GEMINI, snapshot.service)
        assertNull(snapshot.tier)
        assertNull(snapshot.balanceText)
        assertEquals(emptyList<WindowSnapshot>(), snapshot.windows)
        assertFalse(snapshot.isStale)
    }

    @Test
    fun `account name prefers the label then the number`() {
        assertEquals("Work", snapshot(label = "Work", number = 3).accountName(context))
        assertEquals("Account 3", snapshot(number = 3).accountName(context))
        assertNull(snapshot().accountName(context))
    }

    @Test
    fun `title appends the account name to the service name`() {
        assertEquals("Claude · Work", snapshot(label = "Work").title(context))
        assertEquals("Claude", snapshot().title(context))
    }

    @Test
    fun `fresh data has no stale text`() {
        assertFalse(snapshot().isStale)
        assertNull(snapshot().staleText(context))
    }

    @Test
    fun `stale text without date only gives the reason`() {
        assertEquals("Offline", snapshot(status = WidgetDataStatus.OFFLINE).staleText(context))
        assertEquals("Refresh failed", snapshot(status = WidgetDataStatus.FAILED).staleText(context))
    }

    @Test
    fun `stale text with a date appends it`() {
        val fetchedAt = Instant.parse("2020-01-02T03:04:00Z")

        val text = snapshot(status = WidgetDataStatus.OFFLINE, fetchedAt = fetchedAt)
            .staleText(context, ZoneOffset.UTC)

        assertEquals("Offline · ${widgetDataDate(fetchedAt, ZoneOffset.UTC)}", text)
    }

    @Test
    fun `widget date shows only the time for today`() {
        val instant = Instant.parse("2026-11-21T18:05:00Z")

        val text = widgetDataDate(instant, ZoneOffset.UTC, LocalDate.of(2026, 11, 21), Locale.FRANCE)

        assertEquals("18:05", text)
    }

    @Test
    fun `widget date shows date and time for another day`() {
        val instant = Instant.parse("2026-11-20T18:05:00Z")

        val text = widgetDataDate(instant, ZoneOffset.UTC, LocalDate.of(2026, 11, 21), Locale.FRANCE)

        assertEquals("20/11/2026 18:05", text)
    }
}
