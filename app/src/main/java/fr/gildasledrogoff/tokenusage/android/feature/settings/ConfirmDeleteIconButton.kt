package fr.gildasledrogoff.tokenusage.feature.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import fr.gildasledrogoff.tokenusage.R

@Composable
internal fun ConfirmDeleteIconButton(
    contentDescription: String,
    onConfirmed: () -> Unit
) {
    var showWarning by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { showWarning = true }) {
        Icon(
            painter = painterResource(R.drawable.ic_delete_rounded),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.error
        )
    }
    if (showWarning) {
        AlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text(stringResource(R.string.delete_account_warning_title)) },
            confirmButton = {
                TextButton(onClick = {
                    showWarning = false
                    onConfirmed()
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showWarning = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
