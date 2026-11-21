package fr.gildasledrogoff.tokenusage.feature.settings.refresh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.status.StatusCheckSettings
import fr.gildasledrogoff.tokenusage.core.util.ServiceIcon
import fr.gildasledrogoff.tokenusage.core.util.iconRes

@Composable
internal fun StatusCheckSection(
    settings: StatusCheckSettings,
    onToggleAll: (Boolean) -> Unit,
    onToggleProvider: (AiService, Boolean) -> Unit
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
                text = stringResource(R.string.status_check_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.status_check_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SwitchRow(
                label = stringResource(R.string.status_check_all),
                checked = settings.allProviders,
                onToggle = onToggleAll
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StatusCheckSettings.supportedServices.forEach { service ->
                SwitchRow(
                    label = service.displayName,
                    service = service,
                    checked = settings.isEnabled(service),
                    enabled = !settings.allProviders,
                    onToggle = { onToggleProvider(service, it) }
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    service: AiService? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        service?.let { ServiceIcon(iconRes = it.iconRes, contentDescription = null, size = 20.dp) }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
    }
}
