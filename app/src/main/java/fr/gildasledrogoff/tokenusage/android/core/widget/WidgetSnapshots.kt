package fr.gildasledrogoff.tokenusage.core.widget
import android.content.Context
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.data.DisplayPreferencesRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
data class ServiceSnapshot(
    val service: AiService,
    val tier: String?,
    val windows: List<WindowSnapshot>,
    val balanceText: String?,
    val accountLabel: String? = null,
    val accountNumber: Int? = null
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
            .flatMap { service ->
                widgetPrefs.getCachedAccounts(service).map { account ->
                    ServiceSnapshot(
                        service = service,
                        tier = account.tier,
                        windows = account.windows.map { window ->
                            WindowSnapshot(
                                label = window.label,
                                utilization = window.utilization,
                                resetsAtEpochSecond = window.resetsAtEpochSecond,
                                showUsed = showUsed
                            )
                        },
                        balanceText = account.balanceText,
                        accountLabel = account.accountLabel,
                        accountNumber = account.accountNumber
                    )
                }.ifEmpty {
                    listOf(ServiceSnapshot(service, tier = null, windows = emptyList(), balanceText = null))
                }
            }
    }
}
internal fun ServiceSnapshot.accountName(context: Context): String? =
    accountLabel ?: accountNumber?.let { context.getString(R.string.account_n, it) }
internal fun ServiceSnapshot.title(context: Context): String =
    accountName(context)?.let { "${service.displayName} · $it" } ?: service.displayName
