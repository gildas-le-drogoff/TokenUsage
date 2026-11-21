package fr.gildasledrogoff.tokenusage.feature.settings.refresh

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.feature.settings.common.ToggleSection

@Composable
internal fun ReconnectRefreshSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    var askConsent by rememberSaveable { mutableStateOf(false) }
    ToggleSection(
        title = stringResource(R.string.reconnect_refresh_title),
        subtitle = stringResource(
            if (enabled) R.string.reconnect_refresh_subtitle_on
            else R.string.reconnect_refresh_subtitle_off
        ),
        checked = enabled,
        onToggle = { checked ->
            if (checked) askConsent = true else onEnabledChange(false)
        }
    )
    if (askConsent) {
        AlertDialog(
            onDismissRequest = { askConsent = false },
            title = { Text(stringResource(R.string.reconnect_refresh_dialog_title)) },
            text = { Text(stringResource(R.string.reconnect_refresh_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askConsent = false
                    onEnabledChange(true)
                }) {
                    Text(stringResource(R.string.reconnect_refresh_dialog_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { askConsent = false }) {
                    Text(stringResource(R.string.reconnect_refresh_dialog_decline))
                }
            }
        )
    }
}
