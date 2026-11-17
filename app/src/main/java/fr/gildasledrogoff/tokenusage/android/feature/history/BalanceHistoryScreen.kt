package fr.gildasledrogoff.tokenusage.feature.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.ui.components.ChoiceChipRow
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionPoint
import fr.gildasledrogoff.tokenusage.core.domain.model.DeepseekAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.ui.i18n.fxRateNote

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BalanceHistoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: BalanceHistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_history_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(painter = painterResource(R.drawable.ic_arrow_back_rounded), contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                uiState.accounts.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.history_no_account),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.history_no_account_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        AccountSelector(
                            accounts = uiState.accounts,
                            selectedAccountId = uiState.selectedAccountId,
                            onAccountSelected = viewModel::selectAccount
                        )

                        GranularitySelector(
                            granularity = uiState.granularity,
                            onGranularitySelected = viewModel::selectGranularity
                        )

                        CurrentBalanceCard(
                            balance = uiState.latestBalance,
                            currency = uiState.currency,
                            fxRate = uiState.fxRate
                        )

                        HistoryCard(
                            points = uiState.points,
                            granularity = uiState.granularity,
                            currency = uiState.currency
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountSelector(
    accounts: List<DeepseekAccount>,
    selectedAccountId: String?,
    onAccountSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        accounts.forEachIndexed { index, account ->
            FilterChip(
                selected = account.id == selectedAccountId,
                onClick = { onAccountSelected(account.id) },
                label = {
                    Text(account.label.ifBlank { stringResource(R.string.account_n, index + 1) })
                }
            )
        }
    }
}

@Composable
private fun GranularitySelector(
    granularity: ConsumptionGranularity,
    onGranularitySelected: (ConsumptionGranularity) -> Unit
) {
    val options = listOf(
        ConsumptionGranularity.DAILY to stringResource(R.string.granularity_daily),
        ConsumptionGranularity.MONTHLY to stringResource(R.string.granularity_monthly)
    )

    ChoiceChipRow(
        options = options,
        selected = options.first { it.first == granularity },
        label = { it.second },
        onSelect = { onGranularitySelected(it.first) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun CurrentBalanceCard(
    balance: Double?,
    currency: String,
    fxRate: FxRate?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.history_current_balance),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (balance != null) {
                    stringResource(R.string.history_balance_value, balance, currency)
                } else {
                    "—"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (fxRate != null) {
                Text(
                    text = fxRateNote(fxRate) + "\n" + stringResource(R.string.fx_history_approx),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(
    points: List<ConsumptionPoint>,
    granularity: ConsumptionGranularity,
    currency: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (granularity == ConsumptionGranularity.DAILY) {
                    stringResource(R.string.history_daily_title)
                } else {
                    stringResource(R.string.history_monthly_title)
                },
                style = MaterialTheme.typography.titleSmall
            )

            if (points.isEmpty()) {
                Text(
                    text = stringResource(R.string.history_not_enough_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ConsumptionBarChart(points = points, granularity = granularity)
                Text(
                    text = stringResource(
                        R.string.history_peak_total,
                        points.maxOf { it.consumed },
                        currency,
                        points.sumOf { it.consumed },
                        currency
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
