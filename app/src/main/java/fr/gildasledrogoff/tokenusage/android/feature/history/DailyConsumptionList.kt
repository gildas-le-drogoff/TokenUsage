package fr.gildasledrogoff.tokenusage.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionPoint
import java.time.LocalDate

@Composable
fun DailyConsumptionList(
    points: List<ConsumptionPoint>,
    currency: String,
    modifier: Modifier = Modifier,
    days: Int = DEFAULT_DAYS
) {
    if (points.isEmpty()) return
    val today = LocalDate.now().toString()
    val todayAmount = points.firstOrNull { it.periodKey == today }?.consumed ?: 0.0
    val recent = points.filter { it.periodKey != today }.takeLast(days).asReversed()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = stringResource(R.string.history_today),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.history_balance_value, todayAmount, currency),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        recent.forEach { point ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatPeriodKey(point.periodKey, ConsumptionGranularity.DAILY),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.history_balance_value, point.consumed, currency),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private const val DEFAULT_DAYS = 7
