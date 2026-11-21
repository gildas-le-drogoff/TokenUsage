package fr.gildasledrogoff.tokenusage.feature.history

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionPoint
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ConsumptionBarChart(
    points: List<ConsumptionPoint>,
    granularity: ConsumptionGranularity,
    modifier: Modifier = Modifier,
    chartHeight: Dp = CHART_HEIGHT
) {
    if (points.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val barColor = colors.primary
    val pastBarColor = colors.primary.copy(alpha = PAST_BAR_ALPHA)
    val gridColor = colors.outlineVariant
    val averageColor = colors.tertiary
    val labelColor = colors.onSurfaceVariant
    val maxConsumed = points.maxOf { it.consumed }
    val maxValue = maxConsumed.takeIf { it > 0.0 } ?: 1.0
    val average = points.sumOf { it.consumed } / points.size
    val currency = points.last().currency
    val growth = remember(points) { Animatable(0f) }
    LaunchedEffect(points) {
        growth.animateTo(1f, tween(durationMillis = 600, easing = FastOutSlowInEasing))
    }
    val chartDescription = stringResource(
        R.string.chart_cd,
        points.size,
        stringResource(
            if (granularity == ConsumptionGranularity.DAILY) R.string.chart_unit_days else R.string.chart_unit_months
        ),
        maxConsumed
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = chartDescription }
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = stringResource(R.string.chart_max_label, maxConsumed, currency),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor
            )
            Text(
                text = stringResource(R.string.chart_average_label, average, currency),
                style = MaterialTheme.typography.labelSmall,
                color = averageColor
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
        ) {
            val slotWidth = size.width / points.size
            val barWidth = (slotWidth * BAR_FILL_RATIO).coerceAtLeast(2f)
            val plotHeight = size.height * PLOT_HEIGHT_RATIO
            val baseline = size.height
            val radius = (barWidth / 2f).coerceAtMost(6.dp.toPx())
            listOf(0.5f, 1f).forEach { ratio ->
                val y = baseline - plotHeight * ratio
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            points.forEachIndexed { index, point ->
                val barHeight = ((point.consumed / maxValue) * plotHeight * growth.value)
                    .toFloat()
                    .coerceAtLeast(2f)
                val left = index * slotWidth + (slotWidth - barWidth) / 2f
                drawRoundRect(
                    color = if (index == points.lastIndex) barColor else pastBarColor,
                    topLeft = Offset(left, baseline - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(radius, radius)
                )
            }
            val averageY = baseline - (average / maxValue).toFloat() * plotHeight
            drawLine(
                color = averageColor,
                start = Offset(0f, averageY),
                end = Offset(size.width, averageY),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            )
            drawLine(
                gridColor,
                Offset(0f, baseline),
                Offset(size.width, baseline),
                strokeWidth = 1.5.dp.toPx()
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = formatPeriodKey(points.first().periodKey, granularity),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor
            )
            Text(
                text = formatPeriodKey(points.last().periodKey, granularity),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor
            )
        }
    }
}

fun formatPeriodKey(periodKey: String, granularity: ConsumptionGranularity): String {
    val locale = Locale.getDefault()
    return try {
        when (granularity) {
            ConsumptionGranularity.DAILY ->
                LocalDate.parse(periodKey).format(
                    DateTimeFormatter.ofPattern("d MMM", locale)
                )

            ConsumptionGranularity.MONTHLY ->
                YearMonth.parse(periodKey).format(
                    DateTimeFormatter.ofPattern("MMMM yyyy", locale)
                )
        }
    } catch (_: Exception) {
        periodKey
    }
}

private val CHART_HEIGHT = 180.dp
private const val BAR_FILL_RATIO = 0.62f
private const val PLOT_HEIGHT_RATIO = 0.92f
private const val PAST_BAR_ALPHA = 0.7f
