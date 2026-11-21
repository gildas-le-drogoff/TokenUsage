package fr.gildasledrogoff.tokenusage.feature.settings.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.alert.AlertRule
import fr.gildasledrogoff.tokenusage.ui.components.ChoiceChipRow
import fr.gildasledrogoff.tokenusage.ui.i18n.localizedWindowLabel
import kotlin.math.roundToInt

private val HORIZON_OPTIONS_HOURS = listOf(6, 12, 24, 48, 72, 168)

/** Edits one [AlertRule]: quota threshold, window, low balance, depletion horizon. */
@Composable
internal fun AlertRuleEditor(
    rule: AlertRule,
    supportsBalance: Boolean,
    windowLabels: List<String>,
    onRuleChange: (AlertRule) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SwitchRow(stringResource(R.string.alerts_enabled), rule.enabled) { onRuleChange(rule.copy(enabled = it)) }
        if (!rule.enabled) return@Column

        QuotaThresholdRow(rule, onRuleChange)
        if (rule.quotaThreshold != null || rule.depletionHorizonHours != null) {
            WindowFilterRow(rule, windowLabels, onRuleChange)
        }
        if (supportsBalance) BalanceThresholdRow(rule, onRuleChange)
        DepletionHorizonRow(rule, onRuleChange)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun QuotaThresholdRow(rule: AlertRule, onRuleChange: (AlertRule) -> Unit) {
    val threshold = rule.quotaThreshold
    SwitchRow(
        label = if (threshold == null) {
            stringResource(R.string.alerts_quota_threshold)
        } else {
            stringResource(R.string.alerts_quota_threshold_value, (threshold * 100).roundToInt())
        },
        checked = threshold != null
    ) { on -> onRuleChange(rule.copy(quotaThreshold = if (on) AlertRule.DEFAULT_QUOTA_THRESHOLD else null)) }
    if (threshold != null) {
        Slider(
            value = threshold.toFloat(),
            onValueChange = { onRuleChange(rule.copy(quotaThreshold = ((it * 20).roundToInt() / 20.0))) },
            valueRange = 0.5f..1f,
            steps = 9
        )
    }
}

@Composable
private fun WindowFilterRow(rule: AlertRule, windowLabels: List<String>, onRuleChange: (AlertRule) -> Unit) {
    if (windowLabels.size < 2) return
    Text(text = stringResource(R.string.alerts_window_filter), style = MaterialTheme.typography.labelLarge)
    val options: List<String?> = listOf<String?>(null) + windowLabels
    ChoiceChipRow(
        options = options,
        selected = rule.quotaWindowLabel?.takeIf { it in windowLabels },
        label = { it?.let { label -> localizedWindowLabel(label) } ?: stringResource(R.string.alerts_window_all) },
        onSelect = { onRuleChange(rule.copy(quotaWindowLabel = it)) }
    )
}

@Composable
private fun BalanceThresholdRow(rule: AlertRule, onRuleChange: (AlertRule) -> Unit) {
    SwitchRow(stringResource(R.string.alerts_balance_threshold), rule.balanceThreshold != null) { on ->
        onRuleChange(rule.copy(balanceThreshold = if (on) DEFAULT_BALANCE_THRESHOLD else null))
    }
    val threshold = rule.balanceThreshold ?: return
    var text by remember { mutableStateOf(formatAmount(threshold)) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            parseAmount(input)?.let { onRuleChange(rule.copy(balanceThreshold = it)) }
        },
        label = { Text(stringResource(R.string.alerts_balance_field)) },
        supportingText = { Text(stringResource(R.string.alerts_balance_hint)) },
        isError = parseAmount(text) == null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun DepletionHorizonRow(rule: AlertRule, onRuleChange: (AlertRule) -> Unit) {
    val horizon = rule.depletionHorizonHours
    SwitchRow(stringResource(R.string.alerts_depletion), horizon != null) { on ->
        onRuleChange(rule.copy(depletionHorizonHours = if (on) DEFAULT_HORIZON_HOURS else null))
    }
    if (horizon == null) return
    Text(
        text = stringResource(R.string.alerts_depletion_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    ChoiceChipRow(
        options = (HORIZON_OPTIONS_HOURS + horizon).distinct().sorted(),
        selected = horizon,
        label = { horizonLabel(it) },
        onSelect = { onRuleChange(rule.copy(depletionHorizonHours = it)) }
    )
}

@Composable
private fun horizonLabel(hours: Int): String =
    if (hours % 24 == 0) stringResource(R.string.alerts_horizon_days, hours / 24)
    else stringResource(R.string.alerts_horizon_hours, hours)

private const val DEFAULT_BALANCE_THRESHOLD = 5.0
private const val DEFAULT_HORIZON_HOURS = 24

internal fun parseAmount(input: String): Double? =
    input.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0.0 && it.isFinite() }

internal fun formatAmount(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
