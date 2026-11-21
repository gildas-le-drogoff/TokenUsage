package fr.gildasledrogoff.tokenusage.core.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import fr.gildasledrogoff.tokenusage.MainActivity
import fr.gildasledrogoff.tokenusage.R

class QuotaGlanceWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 110.dp),
            DpSize(250.dp, 60.dp),
            DpSize(250.dp, 100.dp),
            DpSize(320.dp, 320.dp)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            currentState<Preferences>()[WidgetRegistry.REVISION_KEY]
            val snapshots = WidgetSnapshots.load(context, appWidgetId)
            val dark = isDarkWidgetTheme(context)
            Palette.use(dark)
            key(dark) {
                GlanceTheme {
                    WidgetRoot(snapshots)
                }
            }
        }
    }

    @Composable
    private fun WidgetRoot(snapshots: List<ServiceSnapshot>) {
        WidgetSurface {
            val size = LocalSize.current
            when {
                size.height < 90.dp -> StripContent(snapshots)
                size.width < 180.dp -> CompactTile(
                    snapshots,
                    GlanceModifier.fillMaxSize().padding(12.dp)
                )

                size.height < 200.dp -> StackedContent(snapshots)
                else -> ScrollableContent(snapshots)
            }
        }
    }

    @Composable
    private fun StripContent(snapshots: List<ServiceSnapshot>) {
        val context = LocalContext.current
        val service = snapshots.firstOrNull()
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (service == null) {
                EmptyState()
            } else {
                val window = service.windows.firstOrNull()
                val staleText = service.staleText(context)
                if (window == null) {
                    Text(
                        text = listOfNotNull(service.balanceText, staleText).joinToString(" · ")
                            .ifEmpty { context.getString(R.string.widget_waiting_for_data) },
                        style = TextStyle(
                            color = if (staleText != null) Palette.warning else Palette.secondary,
                            fontSize = 12.sp
                        ),
                        maxLines = 2
                    )
                } else {
                    val utilization = window.utilization.coerceIn(0f, 1f)
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ServiceDot(service.service.brandColor, 8.dp)
                        Spacer(modifier = GlanceModifier.width(6.dp))
                        Text(
                            text = if (snapshots.size > 1) {
                                "${service.title(context)} +${snapshots.size - 1}"
                            } else {
                                service.title(context)
                            },
                            style = TextStyle(
                                color = Palette.title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1
                        )
                        if (staleText != null) {
                            Spacer(modifier = GlanceModifier.width(4.dp))
                            StaleIcon(staleText)
                        }
                        Spacer(modifier = GlanceModifier.width(8.dp))
                        Spacer(modifier = GlanceModifier.defaultWeight())
                        Text(
                            text = gaugeText(context, window),
                            style = TextStyle(
                                color = utilizationColor(utilization),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = GlanceModifier.width(8.dp))
                        LinearProgressIndicator(
                            progress = barProgress(window),
                            modifier = GlanceModifier
                                .width(72.dp)
                                .height(5.dp)
                                .cornerRadius(2.dp),
                            color = utilizationColor(utilization),
                            backgroundColor = Palette.track
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun StackedContent(snapshots: List<ServiceSnapshot>) {
        Box(modifier = GlanceModifier.fillMaxSize().padding(16.dp)) {
            when {
                snapshots.isEmpty() -> EmptyState()
                snapshots.size == 1 -> Column(modifier = GlanceModifier.fillMaxSize()) {
                    ServiceSection(snapshots.first(), showRefresh = true)
                }

                else -> LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    itemsIndexed(snapshots) { index, service ->
                        Column(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .clickable(actionStartActivity<MainActivity>())
                        ) {
                            if (index > 0) {
                                Spacer(modifier = GlanceModifier.height(4.dp))
                                Divider()
                                Spacer(modifier = GlanceModifier.height(8.dp))
                            }
                            ServiceSection(service, showRefresh = index == 0)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ScrollableContent(snapshots: List<ServiceSnapshot>) {
        if (snapshots.isEmpty()) {
            Box(modifier = GlanceModifier.fillMaxSize().padding(16.dp)) {
                EmptyState()
            }
            return
        }
        LazyColumn(modifier = GlanceModifier.fillMaxSize().padding(16.dp)) {
            itemsIndexed(snapshots) { index, snapshot ->
                Column(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .clickable(actionStartActivity<MainActivity>())
                ) {
                    if (index > 0) {
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        Divider()
                        Spacer(modifier = GlanceModifier.height(8.dp))
                    }
                    ServiceSection(snapshot, showRefresh = index == 0)
                }
            }
        }
    }
}
