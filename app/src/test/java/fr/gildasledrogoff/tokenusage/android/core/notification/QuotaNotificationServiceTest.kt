package fr.gildasledrogoff.tokenusage.core.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.data.DisplayPreferencesRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.LockScreenVisibility
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    application = Application::class,
    qualifiers = "en"
)
class QuotaNotificationServiceTest {
    private lateinit var context: Context
    private lateinit var display: DisplayPreferencesRepositoryImpl
    private lateinit var service: QuotaNotificationService

    private val manager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        display = DisplayPreferencesRepositoryImpl(context)
        service = QuotaNotificationService(context, display)
    }

    private fun quota(service: AiService, vararg utilization: Double, fetchedAt: Instant = Instant.now()) =
        QuotaInfo(
            service = service,
            windows = utilization.mapIndexed { i, u -> UsageWindow("w$i", u, null) },
            fetchedAt = fetchedAt
        )

    private fun monitor(): Notification =
        shadowOf(manager).getNotification(QuotaNotificationService.NOTIFICATION_ID)

    @Suppress("DEPRECATION")
    private fun inflate(notification: Notification): View =
        notification.contentView.apply(context, FrameLayout(context))

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun `both notification channels are created`() {
        val monitorChannel = manager.getNotificationChannel(QuotaNotificationService.CHANNEL_ID)
        val resetChannel = manager.getNotificationChannel(QuotaNotificationService.RESET_CHANNEL_ID)

        assertEquals(NotificationManager.IMPORTANCE_LOW, monitorChannel.importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, resetChannel.importance)
    }

    @Test
    fun `quota notification is ongoing with a refresh action`() {
        service.showQuotaNotification(listOf(quota(AiService.CLAUDE, 0.2)))

        val notification = monitor()
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Refresh", notification.actions.single().title.toString())
        assertEquals(QuotaNotificationService.CHANNEL_ID, notification.channelId)
    }

    @Test
    fun `remaining mode shows the remaining share of the most used window`() {
        service.showQuotaNotification(listOf(quota(AiService.CLAUDE, 0.2, 0.7)))

        val view = inflate(monitor())
        assertEquals("Claude", view.text(R.id.service_name_1))
        assertEquals("30%", view.text(R.id.progress_text_1))
    }

    @Test
    fun `used mode shows the used share`() {
        display.setQuotaDisplayMode(QuotaDisplayMode.USED)

        service.showQuotaNotification(listOf(quota(AiService.CODEX, 0.7)))

        assertEquals("70%", inflate(monitor()).text(R.id.progress_text_1))
    }

    @Test
    fun `utilization is clamped and a quota without windows counts as unused`() {
        service.showQuotaNotification(
            listOf(quota(AiService.CLAUDE, 1.4), quota(AiService.CODEX))
        )

        val view = inflate(monitor())
        assertEquals("0%", view.text(R.id.progress_text_1))
        assertEquals("Codex", view.text(R.id.service_name_2))
        assertEquals("100%", view.text(R.id.progress_text_2))
    }

    @Test
    fun `only the first three services are shown`() {
        service.showQuotaNotification(
            listOf(
                quota(AiService.CLAUDE, 0.1),
                quota(AiService.CODEX, 0.2),
                quota(AiService.GEMINI, 0.3),
                quota(AiService.ZAI, 0.4)
            )
        )

        val view = inflate(monitor())
        assertEquals("Gemini", view.text(R.id.service_name_3))
        assertEquals("70%", view.text(R.id.progress_text_3))
    }

    @Test
    fun `update time says just now for fresh data`() {
        service.showQuotaNotification(listOf(quota(AiService.CLAUDE, 0.1)))

        assertEquals("Updated: just now", inflate(monitor()).text(R.id.update_time))
    }

    @Test
    fun `update time says just now when there is no quota`() {
        service.showQuotaNotification(emptyList())

        assertEquals("Updated: just now", inflate(monitor()).text(R.id.update_time))
    }

    @Test
    fun `update time counts minutes then hours`() {
        service.showQuotaNotification(
            listOf(quota(AiService.CLAUDE, 0.1, fetchedAt = Instant.now().minusSeconds(5 * 60 + 5)))
        )
        assertEquals("Updated: 5 min ago", inflate(monitor()).text(R.id.update_time))

        service.showQuotaNotification(
            listOf(quota(AiService.CLAUDE, 0.1, fetchedAt = Instant.now().minusSeconds(3 * 3600 + 5)))
        )
        assertEquals("Updated: 3h ago", inflate(monitor()).text(R.id.update_time))
    }

    @Test
    fun `reapplying visibility without a previous notification does nothing`() {
        service.reapplyLockScreenVisibility()

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `reapplying visibility updates the active quota notification`() {
        service.showQuotaNotification(listOf(quota(AiService.CLAUDE, 0.1)))
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, monitor().visibility)

        display.setLockScreenVisibility(LockScreenVisibility.SHOW_ALL)
        service.reapplyLockScreenVisibility()

        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, monitor().visibility)
    }

    @Test
    fun `reapplying visibility does not repost a dismissed notification`() {
        service.showQuotaNotification(listOf(quota(AiService.CLAUDE, 0.1)))
        manager.cancel(QuotaNotificationService.NOTIFICATION_ID)

        service.reapplyLockScreenVisibility()

        assertNull(shadowOf(manager).getNotification(QuotaNotificationService.NOTIFICATION_ID))
    }

    @Test
    fun `reset notification names the service and localized window`() {
        display.setLockScreenVisibility(LockScreenVisibility.HIDDEN)

        service.showResetNotification(AiService.CLAUDE, "7-Day")

        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(QuotaNotificationService.RESET_CHANNEL_ID, notification.channelId)
        assertEquals(
            "Claude quota reset",
            notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString()
        )
        assertEquals(
            "7-Day window has been reset. Your quota is fully available.",
            notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()
        )
        assertEquals(NotificationCompat.VISIBILITY_SECRET, notification.visibility)
    }

    @Test
    fun `reset notifications for different windows do not overwrite each other`() {
        service.showResetNotification(AiService.CLAUDE, "5-Hour")
        service.showResetNotification(AiService.CLAUDE, "7-Day")

        assertEquals(2, shadowOf(manager).allNotifications.size)
    }
}
