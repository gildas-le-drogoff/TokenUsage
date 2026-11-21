package fr.gildasledrogoff.tokenusage.feature.settings.backup

import fr.gildasledrogoff.tokenusage.feature.settings.SettingsFields
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.backup.BackupCrypto
import fr.gildasledrogoff.tokenusage.core.backup.PasswordStrength
import fr.gildasledrogoff.tokenusage.core.backup.PasswordStrengthEstimator

@Composable
internal fun BackupSection(
    export: ExportUiState,
    onPinChange: (String) -> Unit,
    onConfirmPinChange: (String) -> Unit,
    onExportZip: () -> Unit,
    onShowQr: () -> Unit,
    importError: ImportError?,
    scanSession: QrScanProgress?,
    onScanQr: () -> Unit,
    onRestartQrScan: () -> Unit,
    onPickZip: () -> Unit
) {
    val pinValid = BackupCrypto.isValidPassword(export.pin)
    val confirmMatches = export.confirmPin.isEmpty() || export.pin == export.confirmPin
    val canExport = pinValid && confirmMatches && !export.isBusy

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.backup_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.backup_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingsFields(
                { modifier ->
                    OutlinedTextField(
                        value = export.pin,
                        onValueChange = onPinChange,
                        label = { Text(stringResource(R.string.field_pin, BackupCrypto.MIN_PASSWORD_LENGTH)) },
                        supportingText = {
                            Column {
                                Text(stringResource(R.string.field_pin_hint))
                                if (export.pin.isNotEmpty()) {
                                    PasswordStrengthIndicator(export.pin)
                                }
                            }
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = modifier,
                        singleLine = true
                    )
                },
                { modifier ->
                    OutlinedTextField(
                        value = export.confirmPin,
                        onValueChange = onConfirmPinChange,
                        label = { Text(stringResource(R.string.field_confirm_pin)) },
                        supportingText = {
                            if (!confirmMatches) {
                                Text(stringResource(R.string.pin_mismatch), color = MaterialTheme.colorScheme.error)
                            } else {
                                Text(stringResource(R.string.field_confirm_pin_hint))
                            }
                        },
                        isError = !confirmMatches,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = modifier,
                        singleLine = true
                    )
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onExportZip,
                    enabled = canExport
                ) {
                    if (export.isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(painter = painterResource(R.drawable.ic_ios_share_rounded), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.action_export_zip))
                }

                OutlinedButton(
                    onClick = onShowQr,
                    enabled = canExport
                ) {
                    if (export.isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(painter = painterResource(R.drawable.ic_qr_code_2_rounded), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.action_export_qr))
                }
            }

            export.error?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = exportErrorMessage(error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.import_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringResource(R.string.import_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (scanSession != null) {
                MultiQrScanProgress(
                    session = scanSession,
                    onScanNext = onScanQr,
                    onRestart = onRestartQrScan
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onPickZip) {
                        Icon(painter = painterResource(R.drawable.ic_file_open_rounded), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_import_zip))
                    }
                    OutlinedButton(onClick = onScanQr) {
                        Icon(painter = painterResource(R.drawable.ic_qr_code_scanner_rounded), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_import_qr))
                    }
                }
            }

            importError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = importErrorMessage(error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun MultiQrScanProgress(
    session: QrScanProgress,
    onScanNext: () -> Unit,
    onRestart: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { session.received.size.toFloat() / session.total },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.import_qr_progress, session.received.size, session.total),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(R.string.import_qr_progress_next, session.nextIndex),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onScanNext) {
                Icon(painter = painterResource(R.drawable.ic_qr_code_scanner_rounded), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_scan_next_qr))
            }
            TextButton(onClick = onRestart) {
                Text(stringResource(R.string.action_restart_qr_scan))
            }
        }
    }
}

@Composable
private fun PasswordStrengthIndicator(password: String) {
    val strength = PasswordStrengthEstimator.strength(password)
    val (fraction, color, labelRes) = when (strength) {
        PasswordStrength.VERY_WEAK -> Triple(0.15f, MaterialTheme.colorScheme.error, R.string.password_strength_very_weak)
        PasswordStrength.WEAK -> Triple(0.35f, MaterialTheme.colorScheme.error, R.string.password_strength_weak)
        PasswordStrength.FAIR -> Triple(0.6f, MaterialTheme.colorScheme.tertiary, R.string.password_strength_fair)
        PasswordStrength.STRONG -> Triple(0.8f, MaterialTheme.colorScheme.primary, R.string.password_strength_strong)
        PasswordStrength.VERY_STRONG -> Triple(1f, MaterialTheme.colorScheme.primary, R.string.password_strength_very_strong)
    }
    Column(modifier = Modifier.padding(top = 6.dp)) {
        LinearProgressIndicator(
            progress = { fraction },
            color = color,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.password_strength_label, stringResource(labelRes)),
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
