package fr.gildasledrogoff.tokenusage.core.workmanager

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkManagerInitializer {
    const val QUOTA_WORK_NAME = "quota_periodic_refresh"
    const val TOKEN_WORK_NAME = "token_periodic_refresh"

    val UNIQUE_WORK_NAMES = listOf(QUOTA_WORK_NAME, TOKEN_WORK_NAME)

    const val MIN_INTERVAL_MINUTES = 15L
    fun schedulePeriodicRefresh(context: Context, intervalMinutes: Long) {
        if (intervalMinutes <= 0) {
            WorkManager.getInstance(context).cancelUniqueWork(QUOTA_WORK_NAME)
            return
        }
        val effectiveInterval = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<QuotaRefreshWorker>(
            effectiveInterval, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                1, TimeUnit.MINUTES
            )
            .addTag("quota_refresh")
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            QUOTA_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun scheduleTokenRefresh(context: Context, intervalMinutes: Long = 30) {
        if (intervalMinutes <= 0) {
            WorkManager.getInstance(context).cancelUniqueWork(TOKEN_WORK_NAME)
            return
        }
        val effectiveInterval = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<TokenRefreshWorker>(
            effectiveInterval, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                1, TimeUnit.MINUTES
            )
            .addTag("token_refresh")
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TOKEN_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
