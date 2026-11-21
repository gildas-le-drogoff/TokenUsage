package fr.gildasledrogoff.tokenusage.core.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import fr.gildasledrogoff.tokenusage.R
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.core.workmanager.QuotaRefreshEntryPoint
import fr.gildasledrogoff.tokenusage.ui.i18n.localizedWindowLabel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter


internal object WidgetRegistry {

    val REVISION_KEY = longPreferencesKey("revision")

    
    private val renderLock = Mutex()

    
    suspend fun refresh(context: Context, glanceId: GlanceId) = renderLock.withLock {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[REVISION_KEY] = System.currentTimeMillis()
        }
        resolve(context, appWidgetId).update(context, glanceId)
    }

    
    suspend fun refreshAll(context: Context) = renderLock.withLock {
        val manager = GlanceAppWidgetManager(context)
        for (widget in listOf(
            QuotaGlanceWidget(),
            QuotaCompactGlanceWidget(),
            ResetGlanceWidget(),
            BalancesGlanceWidget()
        )) {
            manager.getGlanceIds(widget::class.java).forEach { glanceId ->
                updateAppWidgetState(context, glanceId) { prefs ->
                    prefs[REVISION_KEY] = System.currentTimeMillis()
                }
                widget.update(context, glanceId)
            }
        }
    }

    
    fun resolve(context: Context, appWidgetId: Int): GlanceAppWidget {
        val provider = AppWidgetManager.getInstance(context)
            .getAppWidgetInfo(appWidgetId)?.provider?.className
        return when (provider) {
            QuotaCompactWidgetReceiver::class.java.name -> QuotaCompactGlanceWidget()
            ResetWidgetReceiver::class.java.name -> ResetGlanceWidget()
            BalancesWidgetReceiver::class.java.name -> BalancesGlanceWidget()
            else -> QuotaGlanceWidget()
        }
    }
}


class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        WidgetRegistry.refresh(context, glanceId)
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            QuotaRefreshEntryPoint::class.java
        ).quotaRefreshService().refreshNow()
    }
}


@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetActionsEntryPoint {
    fun displayPreferences(): DisplayPreferencesRepository
}


class ToggleWidgetThemeAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        android.util.Log.w("TokenUsageWidget", "toggle: read=${isDarkWidgetTheme(context)}")
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetActionsEntryPoint::class.java
        ).displayPreferences()
            .setThemeMode(if (isDarkWidgetTheme(context)) ThemeMode.LIGHT else ThemeMode.DARK)
        android.util.Log.w("TokenUsageWidget", "toggle: written, now=${isDarkWidgetTheme(context)}")
        WidgetRegistry.refreshAll(context)
        android.util.Log.w("TokenUsageWidget", "toggle: refreshAll done")
    }
}


abstract class TokenUsageGlanceWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)

        provideContent {
            currentState<Preferences>()[WidgetRegistry.REVISION_KEY]
            val snapshots = WidgetSnapshots.load(context, appWidgetId)
            val dark = isDarkWidgetTheme(context)
            Palette.use(dark)
            key(dark) {
                GlanceTheme {
                    WidgetSurface { Content(snapshots) }
                }
            }
        }
    }

    @Composable
    protected abstract fun Content(snapshots: List<ServiceSnapshot>)
}


class QuotaCompactGlanceWidget : TokenUsageGlanceWidget() {

    @Composable
    override fun Content(snapshots: List<ServiceSnapshot>) {
        CompactTile(snapshots, GlanceModifier.fillMaxSize().padding(12.dp))
    }
}

class QuotaCompactWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuotaCompactGlanceWidget()
}


class ResetGlanceWidget : TokenUsageGlanceWidget() {

    @Composable
    override fun Content(snapshots: List<ServiceSnapshot>) {
        ResetContent(snapshots)
    }
}

@Composable
private fun ResetContent(snapshots: List<ServiceSnapshot>) {
    val context = LocalContext.current
    val nowSecond = System.currentTimeMillis() / 1000
    val soonest = snapshots
        .flatMap { service ->
            service.windows.mapNotNull { window ->
                window.resetsAtEpochSecond?.let { Triple(service, window, it) }
            }
        }
        .filter { it.third > nowSecond }
        .minByOrNull { it.third }
    val narrow = LocalSize.current.height < 90.dp

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(
                horizontal = 12.dp,
                vertical = if (narrow) 6.dp else 16.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        if (soonest == null) {
            if (snapshots.isEmpty()) EmptyState() else NoDataState(R.string.widget_no_upcoming_reset)
        } else {
            val (service, window, resetsAt) = soonest
            if (narrow) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            text = service.title(context),
                            style = TextStyle(
                                color = Palette.title,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1
                        )
                        Text(
                            text = context.localizedWindowLabel(window.label),
                            style = TextStyle(color = Palette.secondary, fontSize = 10.sp),
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Text(
                        text = resetCountdownText(context, resetsAt),
                        style = TextStyle(
                            color = Palette.title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            } else {
                Column(
                    modifier = GlanceModifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ServiceDot(service.service.brandColor, 8.dp)
                        Spacer(modifier = GlanceModifier.width(6.dp))
                        Text(
                            text = service.title(context),
                            style = TextStyle(
                                color = Palette.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1
                        )
                        if (snapshots.size > 1) {
                            Spacer(modifier = GlanceModifier.width(4.dp))
                            Text(
                                text = "+${snapshots.size - 1}",
                                style = TextStyle(color = Palette.tertiary, fontSize = 10.sp)
                            )
                        }
                    }
                    Spacer(modifier = GlanceModifier.height(6.dp))
                    Text(
                        text = resetCountdownText(context, resetsAt),
                        style = TextStyle(
                            color = Palette.title,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    Text(
                        text = "${context.localizedWindowLabel(window.label)} · ${resetClock(resetsAt)}",
                        style = TextStyle(color = Palette.secondary, fontSize = 11.sp)
                    )
                }
            }
        }
    }
}


private fun resetClock(epochSecond: Long): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm")
    return formatter.format(Instant.ofEpochSecond(epochSecond).atZone(ZoneId.systemDefault()))
}

class ResetWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ResetGlanceWidget()
}


class BalancesGlanceWidget : TokenUsageGlanceWidget() {

    @Composable
    override fun Content(snapshots: List<ServiceSnapshot>) {
        BalancesContent(snapshots)
    }
}

@Composable
private fun BalancesContent(snapshots: List<ServiceSnapshot>) {
    val balances = snapshots.filter { it.balanceText != null }
    Box(modifier = GlanceModifier.fillMaxSize().padding(16.dp)) {
        if (balances.isEmpty()) {
            if (snapshots.isEmpty()) EmptyState() else NoDataState(R.string.widget_no_balances_to_show)
        } else {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                for ((index, snapshot) in balances.withIndex()) {
                    if (index > 0) {
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        Divider()
                        Spacer(modifier = GlanceModifier.height(8.dp))
                    }
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ServiceDot(snapshot.service.brandColor, 10.dp)
                        Spacer(modifier = GlanceModifier.width(8.dp))
                        Text(
                            text = snapshot.title(LocalContext.current),
                            style = TextStyle(
                                color = Palette.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    Text(
                        text = snapshot.balanceText ?: "",
                        style = TextStyle(color = Palette.secondary, fontSize = 12.sp),
                        maxLines = 3
                    )
                }
            }
        }
    }
}

class BalancesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BalancesGlanceWidget()
}
