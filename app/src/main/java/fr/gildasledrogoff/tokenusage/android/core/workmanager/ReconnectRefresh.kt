package fr.gildasledrogoff.tokenusage.core.workmanager

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Binds
import dagger.Module
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.gildasledrogoff.tokenusage.core.diagnostics.RefreshOrigin
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface ReconnectRefresh {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)

    fun onOffline()

    fun onOnline()
}

@Singleton
class WorkManagerReconnectRefresh @Inject constructor(
    @ApplicationContext private val context: Context
) : ReconnectRefresh {
    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) cancelPending()
    }

    override fun onOffline() {
        if (!isEnabled()) return
        val request = OneTimeWorkRequestBuilder<ReconnectRefreshWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(WORK_NAME)
            .build()
        runCatching {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

    override fun onOnline() = cancelPending()

    private fun cancelPending() {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
    }

    companion object {
        const val WORK_NAME = "quota_reconnect_refresh"
        private const val PREFS_NAME = "tokenusage_reconnect_prefs"
        private const val KEY_ENABLED = "refresh_on_reconnect"
    }
}

@HiltWorker
class ReconnectRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val quotaRefreshService: QuotaRefreshService
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val outcome = quotaRefreshService.refreshNow(RefreshOrigin.RECONNECT)
        return if (outcome.offline && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ReconnectRefreshModule {
    @Binds
    abstract fun bindReconnectRefresh(impl: WorkManagerReconnectRefresh): ReconnectRefresh
}
