package fr.gildasledrogoff.tokenusage.feature.settings.refresh
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.feature.settings.common.ToggleSection
import fr.gildasledrogoff.tokenusage.ui.components.ChoiceChipRow
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RefreshIntervalSection(
    currentMinutes: Long,
    onIntervalChange: (Long) -> Unit,
    batteryExempt: Boolean,
    onRequestExemption: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.refresh_interval_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            val options = listOf(
                15L to stringResource(R.string.refresh_15m),
                30L to stringResource(R.string.refresh_30m),
                60L to stringResource(R.string.refresh_1h),
                0L to stringResource(R.string.refresh_manual)
            )
            ChoiceChipRow(
                options = options,
                selected = options.firstOrNull { it.first == currentMinutes } ?: options[1],
                label = { it.second },
                onSelect = { onIntervalChange(it.first) },
                modifier = Modifier.fillMaxWidth()
            )
            BackgroundRefreshBlock(
                manualOnly = currentMinutes == 0L,
                batteryExempt = batteryExempt,
                onRequestExemption = onRequestExemption,
                onOpenBatterySettings = onOpenBatterySettings
            )
        }
    }
}
@Composable
private fun BackgroundRefreshBlock(
    manualOnly: Boolean,
    batteryExempt: Boolean,
    onRequestExemption: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    Column(
        modifier = Modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.battery_section_title),
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = stringResource(
                if (batteryExempt) R.string.battery_status_exempt else R.string.battery_status_optimized
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        if (!batteryExempt && !manualOnly) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_error_rounded),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.battery_warning_optimized),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else {
            Text(
                text = stringResource(
                    when {
                        manualOnly -> R.string.battery_hint_manual
                        else -> R.string.battery_hint_exempt
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!batteryExempt) {
                OutlinedButton(onClick = onRequestExemption) {
                    Text(stringResource(R.string.battery_action_exempt))
                }
            }
            TextButton(onClick = onOpenBatterySettings) {
                Text(stringResource(R.string.battery_action_system_settings))
            }
        }
    }
}
@Composable
internal fun NotificationsSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    ToggleSection(
        title = stringResource(R.string.notifications_title),
        subtitle = stringResource(R.string.notifications_subtitle),
        checked = enabled,
        onToggle = onToggle
    )
}
