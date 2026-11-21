package fr.gildasledrogoff.tokenusage.core.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.core.alert.AlertType
import fr.gildasledrogoff.tokenusage.core.alert.QuotaAlert
import fr.gildasledrogoff.tokenusage.core.data.DisplayPreferencesRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.LockScreenVisibility
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    application = Application::class,
    qualifiers = "en"
)
class AlertNotifierTest {
    private lateinit var context: Context
    private lateinit var display: DisplayPreferencesRepositoryImpl
    private lateinit var notifier: AlertNotifier
    private val defaultLocale = Locale.getDefault()

    private val manager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
        context = ApplicationProvider.getApplicationContext()
        display = DisplayPreferencesRepositoryImpl(context)
        notifier = AlertNotifier(context, display)
    }

    @After
    fun tearDown() = Locale.setDefault(defaultLocale)

    private fun alert(
        type: AlertType,
        label: String? = null,
        window: String? = null,
        value: Double = 0.85,
        threshold: Double? = null,
        currency: String? = null,
        depletionAt: Instant? = null,
        resetsAt: Instant? = null,
        accountKey: String = "CLAUDE:default"
    ) = QuotaAlert(
        accountKey = accountKey,
        service = AiService.CLAUDE,
        accountLabel = label,
        type = type,
        windowLabel = window,
        currentValue = value,
        threshold = threshold,
        currency = currency,
        depletionAt = depletionAt,
        resetsAt = resetsAt
    )

    private fun posted(alert: QuotaAlert): Notification =
        shadowOf(manager).getNotification(AlertNotifier.notificationId(alert))

    private val Notification.title get() = extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString()
    private val Notification.text get() = extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()

    @Test
    fun `creating the notifier registers the high importance alert channel`() {
        val channel = manager.getNotificationChannel(AlertNotifier.CHANNEL_ID)

        assertEquals("Quota alerts", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun `an empty alert list posts nothing`() {
        notifier.show(emptyList())

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `quota threshold alert shows percentages and localized window`() {
        val alert = alert(AlertType.QUOTA_THRESHOLD, label = "Work", window = "5-Hour", threshold = 0.8)

        notifier.show(listOf(alert))

        val notification = posted(alert)
        assertEquals("Claude · Work: quota at 85%", notification.title)
        assertEquals("5-Hour: 85% used (threshold 80%).", notification.text)
        assertEquals(AlertNotifier.CHANNEL_ID, notification.channelId)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `a blank account label falls back to the service name`() {
        val alert = alert(AlertType.QUOTA_THRESHOLD, label = "  ", window = "7-Day")

        notifier.show(listOf(alert))

        assertEquals("Claude: quota at 85%", posted(alert).title)
        assertEquals("7-Day: 85% used (threshold 0%).", posted(alert).text)
    }

    @Test
    fun `low balance alert formats amounts with the currency`() {
        val alert = alert(AlertType.LOW_BALANCE, value = 1.5, threshold = 2.0, currency = "USD")

        notifier.show(listOf(alert))

        assertEquals("Claude: low balance", posted(alert).title)
        assertEquals("Remaining balance 1.50 USD (threshold 2.00 USD).", posted(alert).text)
    }

    @Test
    fun `low balance alert without currency only shows the amount`() {
        val alert = alert(AlertType.LOW_BALANCE, value = 1.5, currency = " ")

        notifier.show(listOf(alert))

        assertEquals("Remaining balance 1.50 (threshold 0.00).", posted(alert).text)
    }

    @Test
    fun `a depletion date is appended to threshold alerts`() {
        val alert = alert(
            AlertType.LOW_BALANCE,
            value = 1.0,
            threshold = 2.0,
            depletionAt = Instant.parse("2026-11-22T10:00:00Z")
        )

        notifier.show(listOf(alert))

        assertTrue(posted(alert).text, posted(alert).text.contains(" Expected depletion: "))
    }

    @Test
    fun `exhausted alert mentions the upcoming reset`() {
        val alert = alert(
            AlertType.WINDOW_EXHAUSTED,
            window = "5-Hour",
            value = 1.0,
            resetsAt = Instant.now().plusSeconds(2 * 3600 + 30)
        )

        notifier.show(listOf(alert))

        assertEquals("Claude: quota exhausted", posted(alert).title)
        assertEquals("5-Hour: 100% used. Resets in 2h 0m", posted(alert).text)
    }

    @Test
    fun `exhausted alert without reset date has no reset suffix`() {
        val alert = alert(AlertType.WINDOW_EXHAUSTED, window = "5-Hour", value = 1.0)

        notifier.show(listOf(alert))

        assertEquals("5-Hour: 100% used.", posted(alert).text)
    }

    @Test
    fun `restored alert reports the remaining share`() {
        val alert = alert(AlertType.WINDOW_RESTORED, window = "7-Day", value = 0.25)

        notifier.show(listOf(alert))

        assertEquals("Claude: quota available again", posted(alert).title)
        assertEquals("7-Day: quota is available again (75% remaining).", posted(alert).text)
    }

    @Test
    fun `upcoming reset alert reports unused quota`() {
        val alert = alert(
            AlertType.RESET_UPCOMING,
            window = "7-Day",
            value = 0.4,
            resetsAt = Instant.now().plusSeconds(10 * 60 + 30)
        )

        notifier.show(listOf(alert))

        assertEquals("Claude: 7-Day reset soon", posted(alert).title)
        assertEquals(
            "60% of the quota is still unused. Resets in 10m. Unused quota is lost.",
            posted(alert).text
        )
    }

    @Test
    fun `balance depletion forecast uses the balance subject`() {
        val alert = alert(
            AlertType.DEPLETION_FORECAST,
            depletionAt = Instant.parse("2026-11-22T10:00:00Z")
        )

        notifier.show(listOf(alert))

        assertEquals("Claude: depletion expected", posted(alert).title)
        assertTrue(posted(alert).text, posted(alert).text.startsWith("Balance is expected to run out around "))
    }

    @Test
    fun `window depletion forecast uses the window label`() {
        val alert = alert(AlertType.DEPLETION_FORECAST, window = "5-Hour")

        notifier.show(listOf(alert))

        assertEquals("5-Hour is expected to run out around .", posted(alert).text)
    }

    @Test
    fun `silent alerts are posted without sound`() {
        val alert = alert(AlertType.WINDOW_EXHAUSTED, window = "5-Hour", value = 1.0)

        notifier.show(listOf(alert), silent = true)

        assertNotNull(posted(alert))
        assertEquals(
            NotificationCompat.GROUP_ALERT_SUMMARY,
            NotificationCompat.getGroupAlertBehavior(posted(alert))
        )
    }

    @Test
    fun `system lock screen visibility hides details behind a public version`() {
        val alert = alert(AlertType.WINDOW_EXHAUSTED, window = "5-Hour", value = 1.0)

        notifier.show(listOf(alert))

        val notification = posted(alert)
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(
            "A quota alert was raised",
            notification.publicVersion.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()
        )
    }

    @Test
    fun `show all lock screen visibility makes the alert public`() {
        display.setLockScreenVisibility(LockScreenVisibility.SHOW_ALL)
        val alert = alert(AlertType.WINDOW_EXHAUSTED, window = "5-Hour", value = 1.0)

        notifier.show(listOf(alert))

        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, posted(alert).visibility)
        assertNull(posted(alert).publicVersion)
    }

    @Test
    fun `hidden lock screen visibility makes the alert secret`() {
        display.setLockScreenVisibility(LockScreenVisibility.HIDDEN)
        val alert = alert(AlertType.WINDOW_EXHAUSTED, window = "5-Hour", value = 1.0)

        notifier.show(listOf(alert))

        assertEquals(NotificationCompat.VISIBILITY_SECRET, posted(alert).visibility)
    }

    @Test
    fun `notification ids differ per account type and window`() {
        val base = alert(AlertType.QUOTA_THRESHOLD, window = "5-Hour")

        val ids = setOf(
            AlertNotifier.notificationId(base),
            AlertNotifier.notificationId(base.copy(windowLabel = "7-Day")),
            AlertNotifier.notificationId(base.copy(type = AlertType.WINDOW_EXHAUSTED)),
            AlertNotifier.notificationId(base.copy(accountKey = "CLAUDE:other"))
        )

        assertEquals(4, ids.size)
        ids.forEach { assertTrue(it in AlertNotifier.NOTIFICATION_ID_BASE..AlertNotifier.NOTIFICATION_ID_BASE + 0xFFFF) }
        assertNotEquals(AlertNotifier.notificationId(base), AlertNotifier.notificationId(base.copy(windowLabel = null)))
    }
}
