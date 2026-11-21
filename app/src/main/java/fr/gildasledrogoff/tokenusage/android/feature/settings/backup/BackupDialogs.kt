package fr.gildasledrogoff.tokenusage.feature.settings.backup

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.backup.BackupCrypto
import fr.gildasledrogoff.tokenusage.ui.components.SecureValueTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ImportPinDialog(
    state: ImportUiState,
    onPinChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val canImport = BackupCrypto.isUsableForImport(state.pin) && !state.isBusy
    AlertDialog(
        onDismissRequest = { if (!state.isBusy) onDismiss() },
        title = { Text(stringResource(R.string.import_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.import_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                SecureValueTextField(
                    value = state.pin,
                    onValueChange = onPinChange,
                    label = { Text(stringResource(R.string.field_import_pin)) },
                    isError = state.error != null,
                    supportingText = state.error?.let { error ->
                        { Text(importErrorMessage(error), color = MaterialTheme.colorScheme.error) }
                    },
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.import_dialog_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canImport) {
                if (state.isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.action_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isBusy) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
internal fun ExportQrDialog(
    qrCodes: List<String>,
    index: Int,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDismiss: () -> Unit
) {
    val multi = qrCodes.size > 1
    val payload = qrCodes[index]
    val qrImage = rememberQrImage(payload, QR_SIZE_PX)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (multi) stringResource(R.string.qr_dialog_multi_title, index + 1, qrCodes.size)
                else stringResource(R.string.qr_dialog_title)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(if (multi) R.string.qr_dialog_multi_hint else R.string.qr_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrImage != null) {
                        Image(
                            bitmap = qrImage,
                            contentDescription = stringResource(R.string.cd_qr_code),
                            modifier = Modifier.fillMaxWidth(),
                            filterQuality = FilterQuality.None
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.qr_dialog_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            if (multi && index < qrCodes.size - 1) {
                TextButton(onClick = onNext) {
                    Text(stringResource(R.string.action_next_qr))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
            }
        },
        dismissButton = {
            if (multi) {
                TextButton(onClick = onPrevious, enabled = index > 0) {
                    Text(stringResource(R.string.action_previous_qr))
                }
            }
        }
    )
}

@Composable
private fun rememberQrImage(content: String, sizePx: Int): ImageBitmap? {
    var bitmap by remember(content) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(content) {
        withContext(Dispatchers.Default) {
            bitmap = runCatching { generateQrBitmap(content, sizePx) }.getOrNull()
        }
    }
    return bitmap
}

private fun generateQrBitmap(content: String, sizePx: Int): ImageBitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8"
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val black = android.graphics.Color.BLACK
    val white = android.graphics.Color.WHITE
    val colors = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val offset = y * sizePx
        for (x in 0 until sizePx) {
            colors[offset + x] = if (matrix.get(x, y)) black else white
        }
    }
    return Bitmap.createBitmap(colors, sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private const val QR_SIZE_PX = 900
