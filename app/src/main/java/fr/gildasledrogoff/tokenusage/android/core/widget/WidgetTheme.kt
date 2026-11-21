package fr.gildasledrogoff.tokenusage.core.widget
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import fr.gildasledrogoff.tokenusage.MainActivity
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.data.DisplayPreferencesRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.ui.i18n.localizedWindowLabel
import fr.gildasledrogoff.tokenusage.ui.i18n.resetsInText
import java.time.Instant
internal object Palette {
    private class PaletteSet(
        base: Color,
        title: Color,
        secondary: Color,
        tertiary: Color,
        chipBackground: Color,
        track: Color,
        divider: Color,
        iconTint: Color
    ) {
        val isDark: Boolean = base.red < 0.5f
        val base = ColorProvider(base)
        val title = ColorProvider(title)
        val secondary = ColorProvider(secondary)
        val tertiary = ColorProvider(tertiary)
        val chipBackground = ColorProvider(chipBackground)
        val track = ColorProvider(track)
        val divider = ColorProvider(divider)
        val iconTint = ColorProvider(iconTint)
        val danger = ColorProvider(if (isDark) Color(0xFFEF5350) else Color(0xFFC62828))
        val warning = ColorProvider(if (isDark) Color(0xFFFFB74D) else Color(0xFFE65100))
        val safe = ColorProvider(if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32))
    }
    private val light = PaletteSet(
        base = Color(0xFFFFFBFE),
        title = Color(0xFF1C1B1F),
        secondary = Color(0xFF49454F),
        tertiary = Color(0xFF79747E),
        chipBackground = Color(0xFFE8DEF8),
        track = Color(0xFFE7E0EC),
        divider = Color(0xFFCAC4D0),
        iconTint = Color(0xFF49454F)
    )
    private val dark = PaletteSet(
        base = Color(0xDC1C1B1F),
        title = Color.White,
        secondary = Color.White.copy(alpha = 0.72f),
        tertiary = Color.White.copy(alpha = 0.45f),
        chipBackground = Color.White.copy(alpha = 0.15f),
        track = Color.White.copy(alpha = 0.12f),
        divider = Color.White.copy(alpha = 0.12f),
        iconTint = Color.White.copy(alpha = 0.50f)
    )
    private var current: PaletteSet = dark
    fun use(dark: Boolean) {
        current = if (dark) this.dark else light
    }
    val isDark get() = current.isDark
    val base get() = current.base
    val title get() = current.title
    val secondary get() = current.secondary
    val tertiary get() = current.tertiary
    val chipBackground get() = current.chipBackground
    val track get() = current.track
    val divider get() = current.divider
    val iconTint get() = current.iconTint
    val danger get() = current.danger
    val warning get() = current.warning
    val safe get() = current.safe
}
internal fun isDarkWidgetTheme(context: Context): Boolean {
    val resolved = when (DisplayPreferencesRepositoryImpl.themeMode(context)) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    android.util.Log.w("TokenUsageWidget", "isDarkWidgetTheme: ${DisplayPreferencesRepositoryImpl.themeMode(context)} -> $resolved")
    return resolved
}
internal fun utilizationColor(utilization: Float): ColorProvider {
    return when {
        utilization >= 0.85f -> Palette.danger
        utilization >= 0.60f -> Palette.warning
        else -> Palette.safe
    }
}
internal fun gaugeText(context: Context, window: WindowSnapshot): String {
    val usedPct = (window.utilization.coerceIn(0f, 1f) * 100).toInt()
    return if (window.showUsed) {
        context.getString(R.string.gauge_used, usedPct)
    } else {
        context.getString(R.string.gauge_percent_left, 100 - usedPct)
    }
}
internal fun resetLine(context: Context, window: WindowSnapshot): String {
    val resetsAt = window.resetsAtEpochSecond?.let { Instant.ofEpochSecond(it) }
    return context.resetsInText(resetsAt) ?: ""
}
internal fun resetCountdownText(context: Context, epochSecond: Long): String {
    val duration = fr.gildasledrogoff.tokenusage.ui.i18n.resetDuration(Instant.ofEpochSecond(epochSecond))
        ?: return context.getString(R.string.widget_waiting_for_data)
    return when {
        duration.days >= 1 ->
            context.getString(R.string.duration_days_hours, duration.days, duration.hours % 24)
        duration.hours >= 1 ->
            context.getString(R.string.duration_hours_minutes, duration.hours, duration.minutes % 60)
        else -> context.getString(R.string.duration_minutes, duration.minutes)
    }
}
internal fun barProgress(window: WindowSnapshot): Float {
    val utilization = window.utilization.coerceIn(0f, 1f)
    return if (window.showUsed) utilization else 1f - utilization
}
@Composable
internal fun WidgetSurface(content: @Composable () -> Unit) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(20.dp)
            .background(Palette.base)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        if (Palette.isDark) {
            Image(
                provider = ImageProvider(R.drawable.widget_bg_overlay),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize().cornerRadius(20.dp),
                contentScale = ContentScale.FillBounds
            )
        }
        content()
    }
}
@Composable
internal fun CompactTile(snapshots: List<ServiceSnapshot>, modifier: GlanceModifier = GlanceModifier) {
    val context = LocalContext.current
    val service = snapshots.firstOrNull()
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (service == null) {
            EmptyState()
        } else {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically
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
                            style = TextStyle(color = Palette.tertiary, fontSize = 11.sp)
                        )
                    }
                }
                val window = service.windows.firstOrNull()
                Spacer(modifier = GlanceModifier.height(8.dp))
                if (window == null) {
                    Text(
                        text = service.balanceText ?: context.getString(R.string.widget_waiting_for_data),
                        style = TextStyle(
                            color = Palette.secondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        ),
                        maxLines = 3
                    )
                } else {
                    Text(
                        text = gaugeText(context, window),
                        style = TextStyle(
                            color = utilizationColor(window.utilization),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    UsageProgressBar(window, thickness = 6.dp)
                    Spacer(modifier = GlanceModifier.height(6.dp))
                    Text(
                        text = context.localizedWindowLabel(window.label),
                        style = TextStyle(color = Palette.secondary, fontSize = 10.sp)
                    )
                    val resetText = resetLine(context, window)
                    if (resetText.isNotEmpty()) {
                        Spacer(modifier = GlanceModifier.height(2.dp))
                        Text(
                            text = resetText,
                            style = TextStyle(color = Palette.tertiary, fontSize = 9.sp)
                        )
                    }
                }
            }
        }
    }
}
@Composable
internal fun ServiceDot(brandColor: Long, diameter: Dp) {
    Box(
        modifier = GlanceModifier
            .size(diameter)
            .cornerRadius(diameter / 2)
            .background(ColorProvider(Color(brandColor)))
    ) {}
}
@Composable
internal fun EmptyState() {
    Box(
        modifier = GlanceModifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = LocalContext.current.getString(R.string.dashboard_empty_title),
            style = TextStyle(color = Palette.secondary, fontSize = 14.sp)
        )
    }
}
@Composable
internal fun NoDataState(messageRes: Int) {
    Box(
        modifier = GlanceModifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = LocalContext.current.getString(messageRes),
            style = TextStyle(color = Palette.secondary, fontSize = 14.sp)
        )
    }
}
@Composable
internal fun Divider() {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Palette.divider)
    ) {}
}
@Composable
private fun ActionButton(
    iconRes: Int,
    description: String,
    onTap: GlanceModifier,
    tapSize: Dp = 32.dp,
    glyphSize: Dp = 18.dp
) {
    Box(
        modifier = GlanceModifier.size(tapSize).then(onTap),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = description,
            modifier = GlanceModifier.size(glyphSize),
            colorFilter = ColorFilter.tint(Palette.iconTint)
        )
    }
}
@Composable
internal fun ServiceSection(
    snapshot: ServiceSnapshot,
    showRefresh: Boolean
) {
    val service = snapshot.service
    val tier = snapshot.tier
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ServiceDot(service.brandColor, 10.dp)
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = snapshot.title(LocalContext.current),
                style = TextStyle(
                    color = Palette.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1
            )
            if (tier != null) {
                Spacer(modifier = GlanceModifier.width(8.dp))
                Box(
                    modifier = GlanceModifier
                        .cornerRadius(4.dp)
                        .background(Palette.chipBackground)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = tier,
                        style = TextStyle(color = Palette.secondary, fontSize = 11.sp)
                    )
                }
            }
            Spacer(modifier = GlanceModifier.defaultWeight())
            if (showRefresh) {
                ActionButton(
                    R.drawable.ic_contrast_rounded,
                    LocalContext.current.getString(R.string.cd_toggle_theme),
                    GlanceModifier.clickable(actionRunCallback<ToggleWidgetThemeAction>())
                )
                Spacer(modifier = GlanceModifier.width(8.dp))
                ActionButton(
                    R.drawable.ic_refresh,
                    LocalContext.current.getString(R.string.cd_refresh),
                    GlanceModifier.clickable(actionRunCallback<RefreshWidgetAction>())
                )
                Spacer(modifier = GlanceModifier.width(4.dp))
            }
        }
        Spacer(modifier = GlanceModifier.height(8.dp))
        for ((index, window) in snapshot.windows.withIndex()) {
            WindowRow(window, topPadding = if (index > 0) 6.dp else 0.dp)
        }
        if (snapshot.windows.isEmpty()) {
            val balanceText = snapshot.balanceText
            if (balanceText != null) {
                Text(
                    text = balanceText,
                    style = TextStyle(color = Palette.secondary, fontSize = 12.sp),
                    maxLines = 3
                )
            } else {
                Text(
                    text = LocalContext.current.getString(R.string.widget_waiting_for_data),
                    style = TextStyle(color = Palette.tertiary, fontSize = 12.sp)
                )
            }
        }
    }
}
@Composable
internal fun WindowRow(window: WindowSnapshot, topPadding: Dp) {
    val context = LocalContext.current
    val utilization = window.utilization.coerceIn(0f, 1f)
    val resetText = resetLine(context, window)
    Column(modifier = GlanceModifier.fillMaxWidth().padding(top = topPadding)) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = context.localizedWindowLabel(window.label),
                style = TextStyle(color = Palette.secondary, fontSize = 12.sp)
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(
                text = gaugeText(context, window),
                style = TextStyle(
                    color = utilizationColor(utilization),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            )
        }
        Spacer(modifier = GlanceModifier.height(3.dp))
        UsageProgressBar(window)
        if (resetText.isNotEmpty()) {
            Spacer(modifier = GlanceModifier.height(2.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Spacer(modifier = GlanceModifier.defaultWeight())
                Text(
                    text = resetText,
                    style = TextStyle(color = Palette.tertiary, fontSize = 10.sp)
                )
            }
        }
    }
}
@Composable
internal fun UsageProgressBar(window: WindowSnapshot, thickness: Dp = 4.dp) {
    LinearProgressIndicator(
        progress = barProgress(window),
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(thickness)
            .cornerRadius(thickness / 2),
        color = utilizationColor(window.utilization),
        backgroundColor = Palette.track
    )
}
