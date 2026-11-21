package fr.gildasledrogoff.tokenusage.core.diagnostics

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.content.getSystemService
import androidx.work.WorkInfo
import androidx.work.WorkManager
import fr.gildasledrogoff.tokenusage.core.util.BatteryOptimizationHelper
import fr.gildasledrogoff.tokenusage.core.workmanager.WorkManagerInitializer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Lit l'état réel du système (WorkManager, batterie, App Standby, Doze). Aucun accès réseau. */
@Singleton
class BackgroundStateReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: DiagnosticsStore
) : BackgroundStateSource {

    override suspend fun read(): BackgroundSnapshot = withContext(Dispatchers.IO) {
        val power = context.getSystemService<PowerManager>()
        val runs = store.workerRuns().associateBy { it.workName }
        val manufacturer = Build.MANUFACTURER.orEmpty()
        BackgroundSnapshot(
            works = WorkManagerInitializer.UNIQUE_WORK_NAMES.map { name -> workSnapshot(name, runs[name]) },
            ignoringBatteryOptimizations = runCatching {
                BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
            }.getOrNull(),
            standbyBucket = runCatching { context.getSystemService<UsageStatsManager>()?.appStandbyBucket }.getOrNull(),
            backgroundRestricted = runCatching { context.getSystemService<ActivityManager>()?.isBackgroundRestricted }.getOrNull(),
            deviceIdle = power?.isDeviceIdleMode,
            powerSaveMode = power?.isPowerSaveMode,
            manufacturer = manufacturer,
            manufacturerGroup = ManufacturerAdvice.forManufacturer(manufacturer),
            sdkInt = Build.VERSION.SDK_INT
        )
    }

    private fun workSnapshot(name: String, lastRun: WorkerRun?): WorkSnapshot {
        val infos = runCatching {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
        }.getOrNull().orEmpty()
        val info: WorkInfo? = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
        return WorkSnapshot(
            name = name,
            state = info?.state?.name ?: "NOT_SCHEDULED",
            nextRunAtEpochMs = info?.nextScheduleTimeMillis?.takeIf { it != Long.MAX_VALUE && it > 0 },
            runAttemptCount = info?.runAttemptCount ?: 0,
            lastRun = lastRun
        )
    }
}
