package fr.gildasledrogoff.tokenusage.feature.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.alert.TimedValue
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ClaudeUsageCurves(accountId: String, refreshKey: Any?) {
    val viewModel: ClaudeCurvesViewModel = hiltViewModel(key = "claude-curves-$accountId")
    val curves by viewModel.curves.collectAsStateWithLifecycle()
    LaunchedEffect(accountId, refreshKey) { viewModel.load(accountId) }
    Text(
        text = stringResource(R.string.claude_curves_title),
        style = MaterialTheme.typography.titleSmall
    )
    val data = curves
    if (data == null || data.isEmpty) {
        Text(
            text = stringResource(R.string.history_not_enough_data),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val colors = MaterialTheme.colorScheme
    UtilizationLineChart(
        from = data.from,
        to = data.to,
        series = listOf(
            data.fiveHour to colors.primary,
            data.sevenDay to colors.tertiary
        )
    )
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendDot(colors.primary, stringResource(R.string.claude_curve_5h))
        LegendDot(colors.tertiary, stringResource(R.string.claude_curve_7d))
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(modifier = Modifier.size(12.dp)) {
            drawCircle(color, radius = size.minDimension / 2f)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun UtilizationLineChart(
    from: Instant,
    to: Instant,
    series: List<Pair<List<TimedValue>, Color>>,
    modifier: Modifier = Modifier
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val spanMillis = Duration.between(from, to).toMillis().coerceAtLeast(1L).toFloat()
    val formatter = DateTimeFormatter.ofPattern("d MMM", LocalConfiguration.current.locales[0])
        .withZone(ZoneId.systemDefault())
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("100 %", style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            listOf(0f, 0.5f, 1f).forEach { ratio ->
                val y = size.height * (1f - ratio)
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            series.forEach { (values, color) ->
                if (values.isEmpty()) return@forEach
                val path = Path()
                values.forEachIndexed { i, v ->
                    val x = (Duration.between(from, v.at).toMillis() / spanMillis)
                        .coerceIn(0f, 1f) * size.width
                    val y = size.height * (1f - (v.value / 100.0).coerceIn(0.0, 1.0).toFloat())
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatter.format(from), style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text(formatter.format(to), style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
    }
}
