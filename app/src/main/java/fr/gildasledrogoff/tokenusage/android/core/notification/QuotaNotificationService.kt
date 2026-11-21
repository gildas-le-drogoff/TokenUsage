package fr.gildasledrogoff.tokenusage.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.core.workmanager.QuotaRefreshWorker
import fr.gildasledrogoff.tokenusage.ui.i18n.localizedWindowLabel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuotaNotificationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val displayPreferences: DisplayPreferencesRepository
) {
    companion object {
        const val CHANNEL_ID = "quota_monitor"
        const val RESET_CHANNEL_ID = "quota_reset_alert"
        const val NOTIFICATION_ID = 1001
        const val RESET_NOTIFICATION_ID_BASE = 2000
        const val ACTION_REFRESH = "fr.gildasledrogoff.tokenusage.ACTION_REFRESH"
    }

    init {
        createNotificationChannels()
    }

    
    @Volatile
    private var lastQuotas: List<QuotaInfo>? = null

    
    fun reapplyLockScreenVisibility() {
        val quotas = lastQuotas ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.activeNotifications.any { it.id == NOTIFICATION_ID }) {
            showQuotaNotification(quotas)
        }
    }

    
    private fun NotificationCompat.Builder.applyLockScreenVisibility(
        channelId: String,
        @StringRes publicTextRes: Int
    ): NotificationCompat.Builder =
        applyLockScreenVisibility(context, displayPreferences.lockScreenVisibility.value, channelId, publicTextRes)

    private fun createNotificationChannels() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val monitorChannel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_monitor),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_monitor_desc)
            setShowBadge(false)
        }

        val resetChannel = NotificationChannel(
            RESET_CHANNEL_ID,
            context.getString(R.string.notif_channel_reset),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notif_channel_reset_desc)
        }

        manager.createNotificationChannels(listOf(monitorChannel, resetChannel))
    }

    fun showQuotaNotification(quotas: List<QuotaInfo>) {
        lastQuotas = quotas
        val remoteViews = RemoteViews(context.packageName, R.layout.notification_compact)

        val showUsed = displayPreferences.quotaDisplayMode.value == QuotaDisplayMode.USED
        quotas.forEachIndexed { index, quota ->
            if (index >= 3) return@forEachIndexed

            val maxUtilization = (quota.windows.maxOfOrNull { it.utilization } ?: 0.0).coerceIn(0.0, 1.0)
            val usedPct = (maxUtilization * 100).toInt()
            val progress = if (showUsed) usedPct else 100 - usedPct

            when (index) {
                0 -> {
                    remoteViews.setTextViewText(R.id.service_name_1, quota.service.displayName)
                    remoteViews.setProgressBar(R.id.progress_bar_1, 100, progress, false)
                    remoteViews.setTextViewText(R.id.progress_text_1, "${progress}%")
                }
                1 -> {
                    remoteViews.setTextViewText(R.id.service_name_2, quota.service.displayName)
                    remoteViews.setProgressBar(R.id.progress_bar_2, 100, progress, false)
                    remoteViews.setTextViewText(R.id.progress_text_2, "${progress}%")
                }
                2 -> {
                    remoteViews.setTextViewText(R.id.service_name_3, quota.service.displayName)
                    remoteViews.setProgressBar(R.id.progress_bar_3, 100, progress, false)
                    remoteViews.setTextViewText(R.id.progress_text_3, "${progress}%")
                }
            }
        }

        val elapsed = formatElapsed(quotas.firstOrNull()?.fetchedAt)
        remoteViews.setTextViewText(R.id.update_time, context.getString(R.string.notif_updated, elapsed))

        val refreshIntent = Intent(context, RefreshReceiver::class.java).apply {
            action = ACTION_REFRESH
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context, 0, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dashboardIntent = Intent().apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("tokenusage://dashboard")
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val dashboardPendingIntent = PendingIntent.getActivity(
            context, 0, dashboardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quota)
            .setCustomContentView(remoteViews)
            .setContentIntent(dashboardPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_refresh, context.getString(R.string.cd_refresh), refreshPendingIntent)
            .applyLockScreenVisibility(CHANNEL_ID, R.string.notif_public_quota)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun showResetNotification(service: AiService, windowLabel: String) {
        val dashboardIntent = Intent().apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("tokenusage://dashboard")
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val dashboardPendingIntent = PendingIntent.getActivity(
            context, 0, dashboardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, RESET_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_quota)
            .setContentTitle(context.getString(R.string.notif_reset_title, service.displayName))
            .setContentText(context.getString(R.string.notif_reset_body, context.localizedWindowLabel(windowLabel)))
            .setContentIntent(dashboardPendingIntent)
            .setAutoCancel(true)
            .applyLockScreenVisibility(RESET_CHANNEL_ID, R.string.notif_public_reset)
            .build()

        val notificationId = RESET_NOTIFICATION_ID_BASE + "${service.name}_$windowLabel".hashCode().and(0xFFFF)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    private fun formatElapsed(fetchedAt: Instant?): String {
        if (fetchedAt == null) return context.getString(R.string.time_just_now)
        val elapsed = Duration.between(fetchedAt, Instant.now())
        val res = context.resources
        return when {
            elapsed.toMinutes() < 1 -> context.getString(R.string.time_just_now)
            elapsed.toHours() < 1 -> {
                val minutes = elapsed.toMinutes().toInt()
                res.getQuantityString(R.plurals.time_minutes_ago, minutes, minutes)
            }
            else -> {
                val hours = elapsed.toHours().toInt()
                res.getQuantityString(R.plurals.time_hours_ago, hours, hours)
            }
        }
    }
}
