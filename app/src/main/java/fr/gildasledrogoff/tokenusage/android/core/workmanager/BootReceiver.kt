package fr.gildasledrogoff.tokenusage.core.workmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Relance la planification après un redémarrage ou une mise à jour de l'application.
 *
 * Le simple fait que ce récepteur soit appelé démarre le processus, donc
 * `TokenUsageApplication.onCreate` replanifie aussi le travail des quotas avec
 * l'intervalle enregistré. Les travaux étant uniques et planifiés en
 * `ExistingPeriodicWorkPolicy.UPDATE`, un double appel est sans effet.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> WorkManagerInitializer.scheduleTokenRefresh(context)
        }
    }
}
