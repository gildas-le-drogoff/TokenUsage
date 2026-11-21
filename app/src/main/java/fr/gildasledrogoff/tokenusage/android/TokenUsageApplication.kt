package fr.gildasledrogoff.tokenusage

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import fr.gildasledrogoff.tokenusage.core.security.IntegrityGuard
import fr.gildasledrogoff.tokenusage.core.widget.WidgetRegistry
import fr.gildasledrogoff.tokenusage.core.workmanager.RefreshScheduler
import fr.gildasledrogoff.tokenusage.core.workmanager.WorkManagerInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class TokenUsageApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var integrityGuard: IntegrityGuard

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var refreshScheduler: RefreshScheduler
    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.IS_DEBUG) {
            registerActivityLifecycleCallbacks(SecureWindowPolicy)
        }
        integrityGuard.establishBaseline()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { refreshScheduler.applySaved() }
            runCatching { WorkManagerInitializer.scheduleTokenRefresh(this@TokenUsageApplication) }
        }
        registerWidgetThemeSync()
    }

    private fun registerWidgetThemeSync() {
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                        runCatching { WidgetRegistry.refreshAll(applicationContext) }
                    }
                }
            },
            IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private object SecureWindowPolicy : ActivityLifecycleCallbacks {
        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.setRecentsScreenshotEnabled(false)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
