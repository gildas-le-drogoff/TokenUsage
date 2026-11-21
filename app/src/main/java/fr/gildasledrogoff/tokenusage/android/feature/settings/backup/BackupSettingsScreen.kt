package fr.gildasledrogoff.tokenusage.feature.settings.backup

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.feature.settings.common.SettingsSubScreen

@Composable
fun BackupSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val backupWriteFailed = stringResource(R.string.toast_backup_write_failed)
    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val bytes = if (uri != null) viewModel.consumeZipBytes() else null
        if (bytes != null && uri != null) {
            val written = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
            }.getOrDefault(false)
            if (!written) {
                Toast.makeText(context, backupWriteFailed, Toast.LENGTH_LONG).show()
            }
        }
        viewModel.onZipHandled()
    }
    LaunchedEffect(uiState.export.zipReady) {
        if (uiState.export.zipReady) {
            zipLauncher.launch(uiState.export.zipFileName.ifBlank { "tokenusage-config.zip" })
        }
    }
    val scanSession = uiState.importState.scanSession
    val scanPrompt = scanSession?.let { session ->
        stringResource(R.string.import_scan_prompt_multi, session.nextIndex, session.total)
    } ?: stringResource(R.string.import_scan_prompt)
    val qrScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { viewModel.onQrScanned(it) }
    }
    val launchQrScan = {
        viewModel.clearImportError()
        qrScanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(scanPrompt)
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }
    val zipImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val resolver = context.applicationContext.contentResolver
            viewModel.onZipPicked { resolver.openInputStream(uri) }
        }
    }
    val launchZipPick = {
        viewModel.clearImportError()
        zipImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }
    val importedCount = uiState.importState.importedCount
    val importedHistory = uiState.importState.importedHistoryEntries
    val importSuccessMessage = importedCount?.let {
        val credentials = pluralStringResource(R.plurals.import_success, it, it)
        if (importedHistory > 0) {
            credentials + "\n" +
                    pluralStringResource(
                        R.plurals.import_history_added,
                        importedHistory,
                        importedHistory
                    )
        } else {
            credentials
        }
    }
    LaunchedEffect(importedCount) {
        if (importSuccessMessage != null) {
            Toast.makeText(context, importSuccessMessage, Toast.LENGTH_LONG).show()
            viewModel.onImportResultShown()
        }
    }
    SettingsSubScreen(
        title = stringResource(R.string.backup_title),
        onNavigateBack = onNavigateBack
    ) {
        BackupSection(
            export = uiState.export,
            onPinChange = { viewModel.updateExportPin(it) },
            onConfirmPinChange = { viewModel.updateExportConfirmPin(it) },
            onExportZip = { viewModel.prepareZipExport() },
            onShowQr = { viewModel.prepareQrExport() },
            importError = uiState.importState.error.takeUnless { uiState.importState.awaitingPin },
            scanSession = uiState.importState.scanSession,
            onScanQr = launchQrScan,
            onRestartQrScan = { viewModel.restartQrScanSession() },
            onPickZip = launchZipPick
        )
    }
    uiState.export.qrCodes?.let { codes ->
        ExportQrDialog(
            qrCodes = codes,
            index = uiState.export.qrIndex.coerceIn(0, codes.size - 1),
            onNext = { viewModel.nextExportQr() },
            onPrevious = { viewModel.previousExportQr() },
            onDismiss = { viewModel.dismissQrExport() }
        )
    }
    if (uiState.importState.awaitingPin) {
        ImportPinDialog(
            state = uiState.importState,
            onPinChange = { viewModel.updateImportPin(it) },
            onConfirm = { viewModel.confirmImport() },
            onDismiss = { viewModel.dismissImport() }
        )
    }
}
