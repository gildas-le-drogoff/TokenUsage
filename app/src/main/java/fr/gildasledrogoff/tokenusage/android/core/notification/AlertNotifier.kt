package fr.gildasledrogoff.tokenusage.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.alert.AlertType
import fr.gildasledrogoff.tokenusage.core.alert.QuotaAlert
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.ui.i18n.localizedWindowLabel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Shows the threshold / low balance / depletion alerts on their own channel, one per account and alert type. */
@Singleton
class AlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val displayPreferences: DisplayPreferencesRepository
) {
    companion object {
        const val CHANNEL_ID = "quota_alerts"
        const val NOTIFICATION_ID_BASE = 100_000

        /** Stable id: a new alert replaces the previous one of the same account and type. */
        fun notificationId(alert: QuotaAlert): Int =
            NOTIFICATION_ID_BASE + "${alert.accountKey}/${alert.type.name}".hashCode().and(0xFFFF)
    }

    init {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.notif_channel_alerts_desc) }
        )
    }

    fun show(alerts: List<QuotaAlert>) {
        if (alerts.isEmpty()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        alerts.forEach { alert ->
            val (title, body) = texts(alert)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_quota)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(dashboardIntent())
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .applyLockScreenVisibility(
                    context,
                    displayPreferences.lockScreenVisibility.value,
                    CHANNEL_ID,
                    R.string.notif_public_alert
                )
                .build()
            manager.notify(notificationId(alert), notification)
        }
    }

    private fun texts(alert: QuotaAlert): Pair<String, String> {
        val name = accountName(alert)
        val depletion = alert.depletionAt?.let {
            context.getString(R.string.notif_alert_depletion_suffix, formatDate(it))
        }.orEmpty()
        return when (alert.type) {
            AlertType.QUOTA_THRESHOLD -> {
                val window = alert.windowLabel?.let(context::localizedWindowLabel).orEmpty()
                context.getString(R.string.notif_alert_quota_title, name, percent(alert.currentValue)) to
                    context.getString(
                        R.string.notif_alert_quota_body,
                        window,
                        percent(alert.currentValue),
                        percent(alert.threshold ?: 0.0)
                    ) + depletion
            }
            AlertType.LOW_BALANCE ->
                context.getString(R.string.notif_alert_balance_title, name) to
                    context.getString(
                        R.string.notif_alert_balance_body,
                        money(alert.currentValue, alert.currency),
                        money(alert.threshold ?: 0.0, alert.currency)
                    ) + depletion
            AlertType.DEPLETION_FORECAST -> {
                val subject = alert.windowLabel?.let(context::localizedWindowLabel)
                    ?: context.getString(R.string.notif_alert_subject_balance)
                context.getString(R.string.notif_alert_depletion_title, name) to
                    context.getString(
                        R.string.notif_alert_depletion_body,
                        subject,
                        alert.depletionAt?.let(::formatDate).orEmpty()
                    )
            }
        }
    }

    private fun accountName(alert: QuotaAlert): String =
        alert.accountLabel?.takeIf { it.isNotBlank() }?.let { "${alert.service.displayName} · $it" }
            ?: alert.service.displayName

    private fun percent(fraction: Double): Int = (fraction * 100).roundToInt()

    private fun money(amount: Double, currency: String?): String =
        String.format(Locale.getDefault(), "%.2f", amount) + currency?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()

    private fun formatDate(instant: java.time.Instant): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .format(instant.atZone(ZoneId.systemDefault()))

    private fun dashboardIntent(): PendingIntent {
        val intent = Intent().apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse("tokenusage://dashboard")
            setPackage(context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
