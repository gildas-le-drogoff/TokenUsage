package fr.gildasledrogoff.tokenusage.core.widget

import android.content.Context
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.data.DisplayPreferencesRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class ServiceSnapshot(
    val service: AiService,
    val tier: String?,
    val windows: List<WindowSnapshot>,
    val balanceText: String?,
    val accountLabel: String? = null,
    val accountNumber: Int? = null,
    val status: WidgetDataStatus = WidgetDataStatus.FRESH,
    val dataFetchedAt: Instant? = null
) {
    val isStale: Boolean get() = status != WidgetDataStatus.FRESH
}

data class WindowSnapshot(
    val label: String,
    val utilization: Float,
    val resetsAtEpochSecond: Long?,
    val showUsed: Boolean
)

internal object WidgetSnapshots {
    fun load(context: Context, appWidgetId: Int): List<ServiceSnapshot> {
        val widgetPrefs = WidgetPrefsManager(context)
        val showUsed =
            DisplayPreferencesRepositoryImpl.quotaDisplayMode(context) == QuotaDisplayMode.USED
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
                        accountNumber = account.accountNumber,
                        status = account.status,
                        dataFetchedAt = account.dataFetchedAtEpochMillis?.let(Instant::ofEpochMilli)
                    )
                }.ifEmpty {
                    listOf(
                        ServiceSnapshot(
                            service,
                            tier = null,
                            windows = emptyList(),
                            balanceText = null
                        )
                    )
                }
            }
    }
}

internal fun ServiceSnapshot.accountName(context: Context): String? =
    accountLabel ?: accountNumber?.let { context.getString(R.string.account_n, it) }

internal fun ServiceSnapshot.title(context: Context): String =
    accountName(context)?.let { "${service.displayName} · $it" } ?: service.displayName

internal fun ServiceSnapshot.staleText(context: Context, zone: ZoneId = ZoneId.systemDefault()): String? {
    val reason = when (status) {
        WidgetDataStatus.FRESH -> return null
        WidgetDataStatus.OFFLINE -> context.getString(R.string.widget_status_offline)
        WidgetDataStatus.FAILED -> context.getString(R.string.widget_status_failed)
    }
    val date = dataFetchedAt ?: return reason
    return context.getString(R.string.widget_status_with_date, reason, widgetDataDate(date, zone))
}

internal fun widgetDataDate(
    instant: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
    locale: Locale = Locale.getDefault()
): String {
    val dateTime = instant.atZone(zone)
    val formatter = if (dateTime.toLocalDate() == today) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    } else {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    }
    return formatter.withLocale(locale).format(dateTime)
}
