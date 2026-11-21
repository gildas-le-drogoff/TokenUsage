package fr.gildasledrogoff.tokenusage.feature.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.status.ProviderStatus
import fr.gildasledrogoff.tokenusage.core.status.StatusIndicator
import fr.gildasledrogoff.tokenusage.ui.theme.QuotaStatus
import fr.gildasledrogoff.tokenusage.ui.theme.colors
import fr.gildasledrogoff.tokenusage.ui.theme.dimens

private fun StatusIndicator.toQuotaStatus(): QuotaStatus = when (this) {
    StatusIndicator.NONE -> QuotaStatus.HEALTHY
    StatusIndicator.MINOR, StatusIndicator.MAINTENANCE, StatusIndicator.UNKNOWN -> QuotaStatus.LOW
    StatusIndicator.MAJOR, StatusIndicator.CRITICAL -> QuotaStatus.CRITICAL
}

private fun StatusIndicator.labelRes(): Int = when (this) {
    StatusIndicator.NONE -> R.string.provider_status_operational
    StatusIndicator.MINOR -> R.string.provider_status_minor
    StatusIndicator.MAJOR -> R.string.provider_status_major
    StatusIndicator.CRITICAL -> R.string.provider_status_critical
    StatusIndicator.MAINTENANCE -> R.string.provider_status_maintenance
    StatusIndicator.UNKNOWN -> R.string.provider_status_unknown
}

@Composable
fun ProviderStatusBadge(status: ProviderStatus, modifier: Modifier = Modifier) {
    val dimens = MaterialTheme.dimens
    val quotaStatus = status.indicator.toQuotaStatus()
    val label = stringResource(status.indicator.labelRes())
    val description = stringResource(R.string.provider_status_cd, label)
    if (!status.indicator.hasIssue) {
        StatusDot(quotaStatus, modifier.semantics { contentDescription = description })
        return
    }
    val uriHandler = LocalUriHandler.current
    val pair = quotaStatus.colors
    Surface(
        shape = CircleShape,
        color = pair.container,
        modifier = modifier
            .semantics { contentDescription = description }
            .clickable { runCatching { uriHandler.openUri(status.pageUrl) } }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceXs),
            modifier = Modifier.padding(horizontal = dimens.spaceS, vertical = dimens.spaceXs / 2)
        ) {
            StatusDot(quotaStatus)
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = pair.onContainer
            )
        }
    }
}
