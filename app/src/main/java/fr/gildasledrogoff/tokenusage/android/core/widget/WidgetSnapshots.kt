package fr.gildasledrogoff.tokenusage.core.widget

import android.content.Context
import fr.gildasledrogoff.tokenusage.core.data.DisplayPreferencesRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode


data class ServiceSnapshot(
    val service: AiService,
    val tier: String?,
    val windows: List<WindowSnapshot>,
    val balanceText: String?
)

data class WindowSnapshot(
    val label: String,
    val utilization: Float,
    val resetsAtEpochSecond: Long?,
    
    val showUsed: Boolean
)


internal object WidgetSnapshots {

    
    fun load(context: Context, appWidgetId: Int): List<ServiceSnapshot> {
        val widgetPrefs = WidgetPrefsManager(context)
        val showUsed = DisplayPreferencesRepositoryImpl.quotaDisplayMode(context) == QuotaDisplayMode.USED
        return widgetPrefs.getSelectedServices(appWidgetId)
            .sortedBy { it.ordinal }
            .map { service ->
                ServiceSnapshot(
                    service = service,
                    tier = widgetPrefs.getCachedTier(service),
                    windows = widgetPrefs.getCachedLabels(service).map { label ->
                        WindowSnapshot(
                            label = label,
                            utilization = widgetPrefs.getCachedUtilization(service, label),
                            resetsAtEpochSecond = widgetPrefs.getCachedResetsAt(service, label),
                            showUsed = showUsed
                        )
                    },
                    balanceText = widgetPrefs.getCachedBalanceText(service)
                )
            }
    }
}
