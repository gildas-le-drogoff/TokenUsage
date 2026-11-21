package fr.gildasledrogoff.tokenusage.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import fr.gildasledrogoff.tokenusage.core.status.ProviderStatus
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.ui.theme.CRITICAL_REMAINING_THRESHOLD
import fr.gildasledrogoff.tokenusage.ui.theme.LOW_REMAINING_THRESHOLD
import fr.gildasledrogoff.tokenusage.ui.theme.LocalQuotaDisplayMode
import fr.gildasledrogoff.tokenusage.ui.theme.QuotaStatus
import fr.gildasledrogoff.tokenusage.ui.theme.dimens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToHistory: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val consumptionChart by viewModel.consumptionChart.collectAsStateWithLifecycle()
    val providerStatuses by viewModel.providerStatuses.collectAsStateWithLifecycle()
    val celebration by viewModel.celebration.collectAsStateWithLifecycle()
    var deepseekExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(deepseekExpanded) {
        if (deepseekExpanded && consumptionChart is ConsumptionChartUiState.Idle) viewModel.loadConsumption()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshIfCredentialsChanged()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.observeConnectivity()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    val isDarkNow = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                    IconButton(onClick = { viewModel.toggleTheme(isDarkNow) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_contrast_rounded),
                            contentDescription = stringResource(R.string.cd_toggle_theme)
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings_rounded),
                            contentDescription = stringResource(R.string.cd_settings)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = uiState) {
                is DashboardUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is DashboardUiState.Success -> {
                    if (state.cards.isEmpty()) {
                        EmptyState()
                    } else {
                        CardList(
                            cards = state.cards,
                            providerStatuses = providerStatuses,
                            errorBanner = null,
                            lastUpdatedText = formatLastUpdated(state.lastUpdated),
                            consumptionChart = consumptionChart,
                            deepseekExpanded = deepseekExpanded,
                            onToggleDeepseek = { deepseekExpanded = !deepseekExpanded },
                            onSelectConsumptionAccount = viewModel::selectConsumptionAccount,
                            onNavigateToHistory = onNavigateToHistory
                        )
                    }
                }

                is DashboardUiState.PartialSuccess -> {
                    val errorServices = state.errors.keys.joinToString(", ") { it.displayName }
                    CardList(
                        cards = state.cards,
                        providerStatuses = providerStatuses,
                        errorBanner = stringResource(R.string.error_failed_services, errorServices),
                        lastUpdatedText = formatLastUpdated(state.lastUpdated),
                        consumptionChart = consumptionChart,
                        deepseekExpanded = deepseekExpanded,
                        onToggleDeepseek = { deepseekExpanded = !deepseekExpanded },
                        onSelectConsumptionAccount = viewModel::selectConsumptionAccount,
                        onNavigateToHistory = onNavigateToHistory
                    )
                }

                is DashboardUiState.Offline -> {
                    CardList(
                        cards = state.cards,
                        providerStatuses = providerStatuses,
                        errorBanner = stringResource(R.string.dashboard_offline_banner),
                        lastUpdatedText = formatLastUpdated(state.lastUpdated),
                        consumptionChart = consumptionChart,
                        deepseekExpanded = deepseekExpanded,
                        onToggleDeepseek = { deepseekExpanded = !deepseekExpanded },
                        onSelectConsumptionAccount = viewModel::selectConsumptionAccount,
                        onNavigateToHistory = onNavigateToHistory
                    )
                }

                is DashboardUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painter = painterResource(R.drawable.ic_warning_rounded),
                                contentDescription = stringResource(R.string.cd_error),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(MaterialTheme.dimens.spaceS))
                            Text(
                                text = stringResource(
                                    if (state.offline) R.string.dashboard_offline_no_data
                                    else R.string.dashboard_load_failed
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            celebration?.let { event ->
                ResetCelebrationOverlay(event = event, onFinished = viewModel::consumeCelebration)
            }
        }
    }
}

@Composable
private fun CardList(
    cards: List<ServiceCardData>,
    providerStatuses: Map<AiService, ProviderStatus>,
    errorBanner: String?,
    lastUpdatedText: String,
    consumptionChart: ConsumptionChartUiState,
    deepseekExpanded: Boolean,
    onToggleDeepseek: () -> Unit,
    onSelectConsumptionAccount: (String) -> Unit,
    onNavigateToHistory: () -> Unit
) {
    val dimens = MaterialTheme.dimens
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(dimens.cardMinWidth),
        contentPadding = PaddingValues(dimens.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
        verticalItemSpacing = dimens.spaceM,
        modifier = Modifier.fillMaxSize()
    ) {
        if (errorBanner != null) {
            item(span = StaggeredGridItemSpan.FullLine) { ErrorBanner(errorBanner) }
        }
        item(span = StaggeredGridItemSpan.FullLine) {
            Text(
                text = stringResource(R.string.dashboard_last_updated, lastUpdatedText),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item(span = StaggeredGridItemSpan.FullLine) { StatusLegend() }
        items(cards) { card ->
            if (card.hasConsumptionHistory && card.hasData && card.showCurves) {
                ServiceCard(
                    cardData = card,
                    providerStatus = providerStatuses[card.service],
                    expanded = deepseekExpanded,
                    onToggleExpand = onToggleDeepseek
                ) {
                    ConsumptionPanel(
                        state = consumptionChart.forAccount(card.accountId),
                        onSelectAccount = onSelectConsumptionAccount,
                        onOpenHistory = onNavigateToHistory
                    )
                }
            } else if (
                card.service == AiService.CLAUDE && card.hasData && card.showCurves &&
                card.accountId != null
            ) {
                var claudeExpanded by rememberSaveable(card.accountId) { mutableStateOf(false) }
                ServiceCard(
                    cardData = card,
                    providerStatus = providerStatuses[card.service],
                    expanded = claudeExpanded,
                    onToggleExpand = { claudeExpanded = !claudeExpanded }
                ) {
                    ClaudeUsageCurves(accountId = card.accountId, refreshKey = lastUpdatedText)
                }
            } else {
                ServiceCard(cardData = card, providerStatus = providerStatuses[card.service])
            }
        }
    }
}

private fun formatLastUpdated(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .format(instant.atZone(ZoneId.systemDefault()))

@Composable
private fun ErrorBanner(text: String) {
    val dimens = MaterialTheme.dimens
    Surface(
        shape = RoundedCornerShape(dimens.cardCorner / 2),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceS),
            modifier = Modifier.padding(horizontal = dimens.spaceM, vertical = dimens.spaceS)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_error_rounded),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(dimens.inlineIcon + dimens.spaceXs)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusLegend() {
    val dimens = MaterialTheme.dimens
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceM),
        verticalArrangement = Arrangement.spacedBy(dimens.spaceXs),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        val low = (LOW_REMAINING_THRESHOLD * 100).toInt()
        val critical = (CRITICAL_REMAINING_THRESHOLD * 100).toInt()
        val showUsed = LocalQuotaDisplayMode.current == QuotaDisplayMode.USED
        Text(
            text = stringResource(if (showUsed) R.string.quota_legend_title_used else R.string.quota_legend_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (showUsed) {
            listOf(
                QuotaStatus.HEALTHY to stringResource(R.string.quota_legend_ok_used, 100 - low),
                QuotaStatus.LOW to stringResource(
                    R.string.quota_legend_low_used,
                    100 - low,
                    100 - critical
                ),
                QuotaStatus.CRITICAL to stringResource(
                    R.string.quota_legend_critical_used,
                    100 - critical
                )
            )
        } else {
            listOf(
                QuotaStatus.HEALTHY to stringResource(R.string.quota_legend_ok, low),
                QuotaStatus.LOW to stringResource(R.string.quota_legend_low, critical, low),
                QuotaStatus.CRITICAL to stringResource(R.string.quota_legend_critical, critical)
            )
        }.forEach { (status, text) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceXs)
            ) {
                StatusDot(status)
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.dashboard_empty_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(MaterialTheme.dimens.spaceS))
            Text(
                text = stringResource(R.string.dashboard_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun ConsumptionChartUiState.forAccount(accountId: String?): ConsumptionChartUiState {
    if (accountId == null || this !is ConsumptionChartUiState.Ready) return this
    val own = accounts.filter { it.accountId == accountId }
    return if (own.isEmpty()) this else copy(accounts = own, selectedAccountId = accountId)
}
