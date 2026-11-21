package fr.gildasledrogoff.tokenusage.feature.settings.display

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.feature.settings.SettingsFields
import fr.gildasledrogoff.tokenusage.ui.components.ChoiceChipRow
import fr.gildasledrogoff.tokenusage.core.domain.model.DisplayCurrency
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.ui.i18n.appErrorMessage
import fr.gildasledrogoff.tokenusage.ui.i18n.fxRateNote
import fr.gildasledrogoff.tokenusage.ui.i18n.fxRateSourceLabelRes
import fr.gildasledrogoff.tokenusage.ui.theme.dimens


@Composable
internal fun CurrencySettingsSection(
    state: CurrencyConversionUi,
    onDisplayCurrencyChange: (DisplayCurrency) -> Unit,
    onSourceChange: (FxRateSource) -> Unit,
    onApiFetchAllowedChange: (Boolean) -> Unit,
    onManualUsdEurChange: (String) -> Unit,
    onManualUsdCnyChange: (String) -> Unit,
    onRefreshRates: () -> Unit
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
            Text(
                text = stringResource(R.string.fx_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.fx_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ChoiceChipRow(
                options = DisplayCurrency.entries,
                selected = state.displayCurrency,
                label = { it.code ?: stringResource(R.string.fx_currency_original) },
                onSelect = onDisplayCurrencyChange,
                modifier = Modifier.fillMaxWidth()
            )

            AnimatedVisibility(visible = state.displayCurrency == DisplayCurrency.ORIGINAL) {
                Text(
                    text = stringResource(R.string.fx_currency_original_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = state.displayCurrency != DisplayCurrency.ORIGINAL) {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceS)) {
                    Text(
                        text = stringResource(R.string.fx_source_title),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = dimens.spaceS)
                    )
                    RateSourceChoices(selected = state.source, onSelect = onSourceChange)
                    if (state.source == FxRateSource.MANUAL) {
                        ManualRateFields(state, onManualUsdEurChange, onManualUsdCnyChange)
                    } else {
                        ApiFetchConsent(state.apiFetchAllowed, onApiFetchAllowedChange)
                        RemoteRatePreview(state, onRefreshRates)
                    }
                }
            }
        }
    }
}

@Composable
private fun RateSourceChoices(selected: FxRateSource, onSelect: (FxRateSource) -> Unit) {
    val dimens = MaterialTheme.dimens
    Column(modifier = Modifier.selectableGroup()) {
        FxRateSource.entries.forEach { source ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == source,
                        onClick = { onSelect(source) },
                        role = Role.RadioButton
                    )
                    .padding(vertical = dimens.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spaceM)
            ) {
                RadioButton(selected = selected == source, onClick = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(fxRateSourceLabelRes(source)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(fxRateSourceDescriptionRes(source)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}


@Composable
private fun ManualRateFields(
    state: CurrencyConversionUi,
    onManualUsdEurChange: (String) -> Unit,
    onManualUsdCnyChange: (String) -> Unit
) {
    SettingsFields(
        { modifier ->
            ManualRateField(
                value = state.manualUsdEurText,
                invalid = state.manualUsdEurInvalid,
                labelRes = R.string.fx_manual_usd_eur_field,
                hintRes = R.string.fx_manual_usd_eur_hint,
                onValueChange = onManualUsdEurChange,
                modifier = modifier
            )
        },
        { modifier ->
            ManualRateField(
                value = state.manualUsdCnyText,
                invalid = state.manualUsdCnyInvalid,
                labelRes = R.string.fx_manual_usd_cny_field,
                hintRes = R.string.fx_manual_usd_cny_hint,
                onValueChange = onManualUsdCnyChange,
                modifier = modifier
            )
        }
    )
}

@Composable
private fun ManualRateField(
    value: String,
    invalid: Boolean,
    @StringRes labelRes: Int,
    @StringRes hintRes: Int,
    onValueChange: (String) -> Unit,
    modifier: Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        supportingText = {
            Text(stringResource(if (invalid) R.string.fx_manual_invalid else hintRes))
        },
        isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
        singleLine = true
    )
}


@Composable
private fun ApiFetchConsent(allowed: Boolean, onAllowedChange: (Boolean) -> Unit) {
    val dimens = MaterialTheme.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = allowed,
                onClick = { onAllowedChange(!allowed) },
                role = Role.Switch
            )
            .padding(vertical = dimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceM)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.fx_api_consent_title),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.fx_api_consent_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = allowed, onCheckedChange = null)
    }
}


@Composable
private fun RemoteRatePreview(state: CurrencyConversionUi, onRefreshRates: () -> Unit) {
    val dimens = MaterialTheme.dimens
    val rateNotes = state.remoteRates.map { fxRateNote(it) }.joinToString("\n")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceS)
    ) {
        Text(
            text = when {
                !state.apiFetchAllowed && rateNotes.isEmpty() -> stringResource(R.string.fx_api_consent_off)
                state.fetchError != null ->
                    stringResource(R.string.fx_fetch_failed, appErrorMessage(state.fetchError)) +
                        (if (rateNotes.isNotEmpty()) "\n" + rateNotes else "")
                rateNotes.isNotEmpty() -> rateNotes
                state.isFetching -> ""
                else -> stringResource(R.string.fx_rate_none)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.fetchError != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f)
        )
        if (state.isFetching) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else if (state.apiFetchAllowed) {
            TextButton(onClick = onRefreshRates) {
                Text(stringResource(R.string.action_refresh_rate))
            }
        }
    }
}

@StringRes
private fun fxRateSourceDescriptionRes(source: FxRateSource): Int = when (source) {
    FxRateSource.MANUAL -> R.string.fx_source_manual_desc
    FxRateSource.FRANKFURTER -> R.string.fx_source_frankfurter_desc
    FxRateSource.ECB -> R.string.fx_source_ecb_desc
}
