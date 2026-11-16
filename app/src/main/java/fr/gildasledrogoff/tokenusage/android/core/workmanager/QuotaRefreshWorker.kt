package fr.gildasledrogoff.tokenusage.core.workmanager

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.notification.QuotaNotificationService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject


@HiltWorker
class QuotaRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val quotaRefreshService: QuotaRefreshService
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val outcome = quotaRefreshService.refreshNow()
        return when {
            !outcome.anyServiceConfigured -> Result.success()
            outcome.quotas.isEmpty() -> Result.retry()
            else -> Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = android.app.Notification.Builder(applicationContext, QuotaNotificationService.CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.notif_refreshing_title))
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .build()

        return ForegroundInfo(QuotaNotificationService.NOTIFICATION_ID + 1, notification)
    }
}
