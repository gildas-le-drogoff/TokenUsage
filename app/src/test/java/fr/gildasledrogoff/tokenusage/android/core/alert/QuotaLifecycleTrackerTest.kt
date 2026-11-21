package fr.gildasledrogoff.tokenusage.core.alert

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.core.data.DisplayPreferencesRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.notification.AlertNotifier
import fr.gildasledrogoff.tokenusage.core.security.InMemorySharedPreferences
import fr.gildasledrogoff.tokenusage.core.security.SecureStorageFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    application = Application::class,
    qualifiers = "en"
)
class QuotaLifecycleTrackerTest {
    private lateinit var context: Context
    private val prefs = InMemorySharedPreferences()
    private val clock = TestClock()
    private val celebrations = ResetCelebrationBus()
    private lateinit var storage: SecureStorageFactory
    private lateinit var tracker: QuotaLifecycleTracker

    private val notifications
        get() = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .allNotifications

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = mock(SecureStorageFactory::class.java)
        `when`(storage.credentials).thenReturn(prefs)
        tracker = newTracker()
    }

    private fun newTracker(alertStore: AlertPreferencesStore = AlertPreferencesStore { InMemorySharedPreferences() }) =
        QuotaLifecycleTracker(
            storage = storage,
            alertStore = alertStore,
            evaluator = QuotaLifecycleEvaluator(),
            notifier = AlertNotifier(context, DisplayPreferencesRepositoryImpl(context)),
            celebrations = celebrations,
            clock = clock
        )

    private fun quota(label: String, used: Double, resetsInHours: Long) = QuotaInfo(
        service = AiService.CODEX,
        windows = listOf(UsageWindow(label, used, clock.now.plus(Duration.ofHours(resetsInHours)))),
        accountId = "a"
    )

    @Test
    fun `no quota leaves the state untouched`() = runTest {
        tracker.process(emptyList())

        assertTrue(prefs.data.isEmpty())
    }

    @Test
    fun `an exhausted window is notified once and remembered`() = runTest {
        tracker.process(listOf(quota("5-Hour", 1.0, 3)))
        tracker.process(listOf(quota("5-Hour", 1.0, 3)))

        assertEquals(1, notifications.size)
        assertTrue(prefs.data.isNotEmpty())
    }

    @Test
    fun `the state survives a new tracker instance`() = runTest {
        tracker.process(listOf(quota("5-Hour", 1.0, 3)))

        newTracker().process(listOf(quota("5-Hour", 1.0, 3)))

        assertEquals(1, notifications.size)
    }

    @Test
    fun `notifications off still updates the memory without replaying later`() = runTest {
        tracker.process(listOf(quota("5-Hour", 1.0, 3)), notify = false)
        assertTrue(notifications.isEmpty())

        tracker.process(listOf(quota("5-Hour", 1.0, 3)), notify = true)

        assertTrue(notifications.isEmpty())
    }

    @Test
    fun `a corrupted state is ignored and rebuilt`() = runTest {
        prefs.edit().putString("quota_lifecycle_state_v1", "{broken").apply()

        tracker.process(listOf(quota("5-Hour", 1.0, 3)))

        assertEquals(1, notifications.size)
        assertTrue(prefs.getString("quota_lifecycle_state_v1", null)!!.startsWith("{\"windows\""))
    }

    @Test
    fun `a weekly reset triggers a celebration`() = runTest {
        tracker.process(listOf(quota("7-Day", 0.5, 0).copy(windows = listOf(
            UsageWindow("7-Day", 0.5, clock.now.minus(Duration.ofMinutes(5)))
        ))))
        assertNull(celebrations.pending.value)

        tracker.process(listOf(quota("7-Day", 0.0, 7 * 24)))

        assertEquals(AiService.CODEX, celebrations.pending.value?.service)
    }

    @Test
    fun `a storage failure is swallowed`() = runTest {
        `when`(storage.credentials).thenThrow(IllegalStateException("vault locked"))

        tracker.process(listOf(quota("5-Hour", 1.0, 3)))

        assertTrue(notifications.isEmpty())
    }
}
