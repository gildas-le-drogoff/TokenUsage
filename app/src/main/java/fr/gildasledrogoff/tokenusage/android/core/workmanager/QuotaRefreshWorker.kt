package fr.gildasledrogoff.tokenusage.core.workmanager

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.diagnostics.DiagnosticsStore
import fr.gildasledrogoff.tokenusage.core.diagnostics.RefreshOrigin
import fr.gildasledrogoff.tokenusage.core.notification.QuotaNotificationService

@HiltWorker
class QuotaRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val quotaRefreshService: QuotaRefreshService,
    private val diagnosticsStore: DiagnosticsStore
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val outcome = quotaRefreshService.refreshNow(RefreshOrigin.PERIODIC_WORKER)
        val allSucceeded = !outcome.anyServiceConfigured || outcome.quotas.isNotEmpty()
        val decision = WorkRetryPolicy.decide(allSucceeded, runAttemptCount)
        diagnosticsStore.recordWorkerRun(
            WorkManagerInitializer.QUOTA_WORK_NAME,
            decision.label,
            runAttemptCount
        )
        return if (decision == WorkRetryPolicy.Decision.RETRY) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = android.app.Notification.Builder(
            applicationContext,
            QuotaNotificationService.CHANNEL_ID
        )
            .setContentTitle(applicationContext.getString(R.string.notif_refreshing_title))
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .build()
        return ForegroundInfo(
            QuotaNotificationService.NOTIFICATION_ID + 1,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }
}
