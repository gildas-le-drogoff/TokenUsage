package fr.gildasledrogoff.tokenusage.feature.settings.accounts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.util.ServiceIcon
import fr.gildasledrogoff.tokenusage.ui.i18n.appErrorMessage

@Composable
internal fun ValidationResultLabel(result: ValidationResult?, modifier: Modifier = Modifier) {
    val valid = Color(0xFF4CAF50)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        when (result) {
            is ValidationResult.Success -> {
                Icon(
                    painter = painterResource(R.drawable.ic_check_circle_rounded),
                    contentDescription = stringResource(R.string.cd_valid),
                    tint = valid,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    stringResource(R.string.status_valid),
                    color = valid,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            is ValidationResult.Failure -> {
                Icon(
                    painter = painterResource(R.drawable.ic_error_rounded),
                    contentDescription = stringResource(R.string.cd_invalid),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    appErrorMessage(result.error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            null -> {}
        }
    }
}

@Composable
internal fun accountStatus(count: Int): String = when (count) {
    0 -> stringResource(R.string.status_not_in_use)
    else -> pluralStringResource(R.plurals.accounts_in_use, count, count)
}

@Composable
internal fun ExpandableSectionHeader(
    iconRes: Int,
    iconContentDescription: String,
    title: String,
    statusLabel: String,
    configured: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "expandChevron"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = if (expanded) {
                    stringResource(R.string.cd_collapse)
                } else {
                    stringResource(R.string.cd_expand)
                },
                onClick = onToggle
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ServiceIcon(
            iconRes = iconRes,
            contentDescription = iconContentDescription,
            size = 24.dp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_check_circle_rounded),
            contentDescription = stringResource(
                if (configured) R.string.status_in_use else R.string.status_not_in_use
            ),
            tint = if (configured) {
                Color(0xFF4CAF50)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            painter = painterResource(R.drawable.ic_expand_more_rounded),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(24.dp)
                .rotate(chevronRotation)
        )
    }
}
