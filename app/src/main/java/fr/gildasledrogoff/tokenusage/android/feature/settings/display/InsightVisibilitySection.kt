package fr.gildasledrogoff.tokenusage.feature.settings.display

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.DashboardInsight
import fr.gildasledrogoff.tokenusage.core.util.ServiceIcon
import fr.gildasledrogoff.tokenusage.core.util.iconRes

@Composable
internal fun InsightVisibilitySection(
    providers: List<ProviderInsightsUi>,
    onVisibilityChange: (DashboardInsight, AiService, accountId: String?, visible: Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.insights_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(
                    if (providers.isEmpty()) R.string.insights_empty else R.string.insights_hint
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            providers.forEach { provider ->
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                ProviderInsightsBlock(provider, onVisibilityChange)
            }
        }
    }
}

@Composable
private fun ProviderInsightsBlock(
    provider: ProviderInsightsUi,
    onVisibilityChange: (DashboardInsight, AiService, String?, Boolean) -> Unit
) {
    val service = provider.service
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ServiceIcon(iconRes = service.iconRes, contentDescription = null, size = 20.dp)
            Text(
                text = service.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        InsightChips(
            supportsPace = provider.supportsPace,
            supportsCurves = provider.supportsCurves,
            paceShown = provider.paceShown,
            curvesShown = provider.curvesShown,
            paceEnabled = true,
            curvesEnabled = true,
            onChange = { insight, visible -> onVisibilityChange(insight, service, null, visible) }
        )
        if (provider.accounts.size > 1) {
            provider.accounts.forEach { account ->
                Column(
                    modifier = Modifier.padding(start = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = account.label
                            ?: account.number?.let { stringResource(R.string.account_n, it) }
                            ?: stringResource(R.string.account_default),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    InsightChips(
                        supportsPace = provider.supportsPace,
                        supportsCurves = provider.supportsCurves,
                        paceShown = provider.paceShown && account.paceShown,
                        curvesShown = provider.curvesShown && account.curvesShown,
                        paceEnabled = provider.paceShown,
                        curvesEnabled = provider.curvesShown,
                        onChange = { insight, visible ->
                            onVisibilityChange(insight, service, account.accountId, visible)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InsightChips(
    supportsPace: Boolean,
    supportsCurves: Boolean,
    paceShown: Boolean,
    curvesShown: Boolean,
    paceEnabled: Boolean,
    curvesEnabled: Boolean,
    onChange: (DashboardInsight, Boolean) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (supportsPace) {
            InsightChip(
                label = stringResource(R.string.insights_pace),
                shown = paceShown,
                enabled = paceEnabled,
                onToggle = { onChange(DashboardInsight.PACE, !paceShown) }
            )
        }
        if (supportsCurves) {
            InsightChip(
                label = stringResource(R.string.insights_curves),
                shown = curvesShown,
                enabled = curvesEnabled,
                onToggle = { onChange(DashboardInsight.CURVES, !curvesShown) }
            )
        }
    }
}

@Composable
private fun InsightChip(label: String, shown: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = shown,
        enabled = enabled,
        onClick = onToggle,
        label = { Text(label) },
        leadingIcon = if (shown) {
            {
                Icon(
                    painter = painterResource(R.drawable.ic_check_circle_rounded),
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                )
            }
        } else null
    )
}
