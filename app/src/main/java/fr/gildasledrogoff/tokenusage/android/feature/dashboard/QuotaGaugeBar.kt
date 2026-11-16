package fr.gildasledrogoff.tokenusage.feature.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageSeverity
import fr.gildasledrogoff.tokenusage.ui.i18n.localizedWindowLabel
import fr.gildasledrogoff.tokenusage.ui.i18n.resetsInLabel
import fr.gildasledrogoff.tokenusage.ui.theme.LocalQuotaDisplayMode
import fr.gildasledrogoff.tokenusage.ui.theme.QuotaStatus
import fr.gildasledrogoff.tokenusage.ui.theme.colors
import fr.gildasledrogoff.tokenusage.ui.theme.dimens
import fr.gildasledrogoff.tokenusage.ui.theme.quotaStatusOf
import java.time.Instant
import kotlin.math.roundToInt


@Composable
fun QuotaGaugeBar(
    utilization: Double,
    label: String? = null,
    resetsAt: Instant? = null,
    severity: UsageSeverity = UsageSeverity.NORMAL,
    
    detail: String? = null,
    modifier: Modifier = Modifier
) {
    val dimens = MaterialTheme.dimens
    val remaining = (1.0 - utilization).coerceIn(0.0, 1.0)
    val remainingPct = (remaining * 100).roundToInt()
    val usedPct = if (utilization > 1.0) (utilization * 100).roundToInt() else 100 - remainingPct
    val status = quotaStatusOf(remaining, severity)
    val showUsed = LocalQuotaDisplayMode.current == QuotaDisplayMode.USED

    val animatedProgress by animateFloatAsState(
        targetValue = if (showUsed) 1f - remaining.toFloat() else remaining.toFloat(),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "gauge_progress"
    )
    val barColor by animateColorAsState(status.colors.main, tween(400), label = "gauge_color")
    val trackColor by animateColorAsState(status.colors.container, tween(400), label = "gauge_track")

    val windowLabel = label?.let { localizedWindowLabel(it) }
    val resetText = resetsInLabel(resetsAt)
    val description = stringResource(
        if (showUsed) R.string.gauge_cd_used else R.string.gauge_cd,
        windowLabel.orEmpty(),
        if (showUsed) usedPct else remainingPct,
        quotaStatusLabel(status)
    ) + (resetText?.let { ", $it" } ?: "")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = windowLabel.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .alignByBaseline()
            )
            Text(
                text = stringResource(R.string.gauge_percent_value, if (showUsed) usedPct else remainingPct),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = barColor,
                modifier = Modifier.alignByBaseline()
            )
            Text(
                text = " " + stringResource(if (showUsed) R.string.gauge_used_suffix else R.string.gauge_left_suffix),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimens.gaugeThickness)
                .clip(CircleShape)
                .background(trackColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .clip(CircleShape)
                    .background(barColor)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceXs)
        ) {
            Text(
                text = detail ?: if (showUsed) {
                    stringResource(R.string.gauge_percent_left, remainingPct)
                } else {
                    stringResource(R.string.gauge_used, usedPct)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (resetText != null) {
                Icon(
                    painter = painterResource(R.drawable.ic_schedule_rounded),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(dimens.inlineIcon)
                )
                Text(
                    text = resetText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun quotaStatusLabel(status: QuotaStatus): String = stringResource(
    when (status) {
        QuotaStatus.HEALTHY -> R.string.quota_status_ok
        QuotaStatus.LOW -> R.string.quota_status_low
        QuotaStatus.CRITICAL -> R.string.quota_status_critical
    }
)
