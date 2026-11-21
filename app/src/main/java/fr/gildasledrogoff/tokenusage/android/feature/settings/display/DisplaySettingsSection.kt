package fr.gildasledrogoff.tokenusage.feature.settings.display

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.ui.components.ChoiceChipRow
import fr.gildasledrogoff.tokenusage.ui.theme.dimens

@Composable
internal fun DisplaySettingsSection(
    themeMode: ThemeMode,
    quotaDisplayMode: QuotaDisplayMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onQuotaDisplayModeChange: (QuotaDisplayMode) -> Unit
) {
    val dimens = MaterialTheme.dimens
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceS)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceS)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_contrast_rounded),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.display_title),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Text(
                text = stringResource(R.string.display_theme),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = dimens.spaceS)
            )
            ChoiceRow(
                options = ThemeMode.entries,
                selected = themeMode,
                labelRes = ::themeModeLabelRes,
                onSelect = onThemeModeChange
            )
            Text(
                text = stringResource(R.string.display_quota),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = dimens.spaceS)
            )
            ChoiceRow(
                options = QuotaDisplayMode.entries,
                selected = quotaDisplayMode,
                labelRes = ::quotaDisplayModeLabelRes,
                onSelect = onQuotaDisplayModeChange
            )
            Text(
                text = stringResource(R.string.display_quota_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    labelRes: (T) -> Int,
    onSelect: (T) -> Unit
) {
    ChoiceChipRow(
        options = options,
        selected = selected,
        label = { stringResource(labelRes(it)) },
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth()
    )
}

@StringRes
private fun themeModeLabelRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

@StringRes
private fun quotaDisplayModeLabelRes(mode: QuotaDisplayMode): Int = when (mode) {
    QuotaDisplayMode.REMAINING -> R.string.quota_mode_remaining
    QuotaDisplayMode.USED -> R.string.quota_mode_used
}
