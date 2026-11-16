package fr.gildasledrogoff.tokenusage.feature.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import fr.gildasledrogoff.tokenusage.feature.history.ConsumptionBarChart
import fr.gildasledrogoff.tokenusage.ui.theme.dimens


@Composable
fun ConsumptionPanel(
    state: ConsumptionChartUiState,
    onSelectAccount: (String) -> Unit,
    onOpenHistory: () -> Unit
) {
    val dimens = MaterialTheme.dimens

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        text = stringResource(R.string.history_daily_title),
        style = MaterialTheme.typography.titleSmall
    )

    when (state) {
        ConsumptionChartUiState.Idle, ConsumptionChartUiState.Loading -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is ConsumptionChartUiState.Ready -> {
            if (state.accounts.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(dimens.spaceS)
                ) {
                    state.accounts.forEachIndexed { index, account ->
                        FilterChip(
                            selected = account.accountId == state.selectedAccountId,
                            onClick = { onSelectAccount(account.accountId) },
                            label = {
                                Text(account.label.ifBlank { stringResource(R.string.account_n, index + 1) })
                            }
                        )
                    }
                }
            }

            val selected = state.selected
            if (selected == null || selected.points.isEmpty()) {
                Text(
                    text = stringResource(R.string.history_not_enough_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ConsumptionBarChart(
                    points = selected.points,
                    granularity = ConsumptionGranularity.DAILY,
                    chartHeight = dimens.inlineChartHeight
                )
                Text(
                    text = stringResource(
                        R.string.history_peak_total,
                        selected.points.maxOf { it.consumed },
                        selected.currency,
                        selected.points.sumOf { it.consumed },
                        selected.currency
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onOpenHistory, modifier = Modifier.align(Alignment.CenterVertically)) {
            Text(stringResource(R.string.dashboard_open_history))
        }
    }
}
