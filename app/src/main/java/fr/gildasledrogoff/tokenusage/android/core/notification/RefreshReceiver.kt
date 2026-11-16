package fr.gildasledrogoff.tokenusage.core.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import fr.gildasledrogoff.tokenusage.core.workmanager.QuotaRefreshEntryPoint
import fr.gildasledrogoff.tokenusage.core.workmanager.QuotaRefreshService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != QuotaNotificationService.ACTION_REFRESH) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    QuotaRefreshEntryPoint::class.java
                ).quotaRefreshService().refreshNow()
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }
    }
}
