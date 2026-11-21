package fr.gildasledrogoff.tokenusage.feature.settings.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.gildasledrogoff.tokenusage.core.backup.BackupCrypto
import fr.gildasledrogoff.tokenusage.core.backup.BackupCryptoException
import fr.gildasledrogoff.tokenusage.core.backup.ConfigBackupManager
import fr.gildasledrogoff.tokenusage.core.backup.InvalidBackupException
import fr.gildasledrogoff.tokenusage.core.backup.QrChunkCodec
import fr.gildasledrogoff.tokenusage.core.backup.QrPayloadTooLargeException
import fr.gildasledrogoff.tokenusage.core.workmanager.RefreshScheduler
import fr.gildasledrogoff.tokenusage.feature.settings.accounts.AccountsSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: ConfigBackupManager,
    private val refreshScheduler: RefreshScheduler,
    private val accountsSync: AccountsSync
) : ViewModel() {
    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()
    private var pendingZipBytes: ByteArray? = null
    fun updateExportPin(pin: String) {
        _uiState.update {
            it.copy(export = it.export.copy(pin = pin, error = null, zipReady = false))
        }
    }

    fun updateExportConfirmPin(pin: String) {
        _uiState.update {
            it.copy(export = it.export.copy(confirmPin = pin, error = null, zipReady = false))
        }
    }

    fun dismissQrExport() {
        _uiState.update { it.copy(export = it.export.copy(qrCodes = null, qrIndex = 0)) }
    }

    fun nextExportQr() {
        _uiState.update {
            val max = (it.export.qrCodes?.size ?: 1) - 1
            it.copy(export = it.export.copy(qrIndex = (it.export.qrIndex + 1).coerceAtMost(max)))
        }
    }

    fun previousExportQr() {
        _uiState.update {
            it.copy(export = it.export.copy(qrIndex = (it.export.qrIndex - 1).coerceAtLeast(0)))
        }
    }

    fun prepareZipExport() {
        if (!validateExportPin()) return
        flushPendingChanges()
        setExportBusy(true)
        val pin = _uiState.value.export.pin
        viewModelScope.launch(Dispatchers.Default) {
            val result = runCatching { backupManager.createZipBytes(pin) }
            withContext(Dispatchers.Main) { setExportBusy(false) }
            result.fold(
                onSuccess = { bytes ->
                    pendingZipBytes = bytes
                    _uiState.update {
                        it.copy(
                            export = it.export.copy(
                                zipReady = true,
                                zipFileName = defaultZipFileName(),
                                error = null
                            )
                        )
                    }
                },
                onFailure = { exportFailed(it) }
            )
        }
    }

    fun consumeZipBytes(): ByteArray? {
        val bytes = pendingZipBytes
        pendingZipBytes = null
        return bytes
    }

    fun onZipHandled() {
        _uiState.update { it.copy(export = it.export.copy(zipReady = false)) }
    }

    fun prepareQrExport() {
        if (!validateExportPin()) return
        flushPendingChanges()
        setExportBusy(true)
        val pin = _uiState.value.export.pin
        viewModelScope.launch(Dispatchers.Default) {
            val result = runCatching { backupManager.createQrCodes(pin) }
            withContext(Dispatchers.Main) { setExportBusy(false) }
            result.fold(
                onSuccess = { codes ->
                    _uiState.update {
                        it.copy(
                            export = it.export.copy(
                                qrCodes = codes,
                                qrIndex = 0,
                                error = null
                            )
                        )
                    }
                },
                onFailure = { exportFailed(it) }
            )
        }
    }

    fun isExportPinValid(): Boolean {
        val export = _uiState.value.export
        return BackupCrypto.isValidPassword(export.pin) && (export.confirmPin.isEmpty() || export.pin == export.confirmPin)
    }

    private fun validateExportPin(): Boolean {
        val export = _uiState.value.export
        val error = when {
            !BackupCrypto.isValidPassword(export.pin) -> ExportError.PinTooShort
            export.confirmPin.isNotEmpty() && export.pin != export.confirmPin -> ExportError.PinMismatch
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(export = it.export.copy(error = error)) }
        }
        return error == null
    }

    private fun setExportBusy(busy: Boolean) {
        _uiState.update { it.copy(export = it.export.copy(isBusy = busy, error = null)) }
    }

    private fun exportFailed(error: Throwable) {
        val mapped =
            if (error is QrPayloadTooLargeException) ExportError.QrTooLarge else ExportError.Failed
        _uiState.update { it.copy(export = it.export.copy(error = mapped)) }
    }

    private var pendingImportContainer: ByteArray? = null
    private val qrScanChunks = mutableMapOf<Int, QrChunkCodec.Chunk>()
    private var qrSessionHash: String? = null
    fun onQrScanned(contents: String) {
        val text = contents.trim()
        if (QrChunkCodec.isMultiQr(text)) {
            onMultiQrChunkScanned(text)
            return
        }
        if (_uiState.value.importState.scanSession != null) {
            setImportError(ImportError.QrMismatch)
            return
        }
        onContainerLoaded(runCatching { backupManager.parseQrPayload(text) }.getOrNull())
    }

    private fun onMultiQrChunkScanned(text: String) {
        val chunk = runCatching { QrChunkCodec.parseChunk(text) }.getOrNull()
        if (chunk == null) {
            setImportError(ImportError.NotABackup)
            return
        }
        val session = _uiState.value.importState.scanSession
        if (session == null) {
            qrScanChunks.clear()
            qrSessionHash = chunk.fullHash
            _uiState.update {
                it.copy(
                    importState = it.importState.copy(
                        scanSession = QrScanProgress(
                            chunk.total,
                            emptySet()
                        )
                    )
                )
            }
        } else if (session.total != chunk.total || qrSessionHash != chunk.fullHash) {
            setImportError(ImportError.QrMismatch)
            return
        }
        qrScanChunks[chunk.index] = chunk
        if (qrScanChunks.size == chunk.total) {
            completeQrScanSession()
        } else {
            publishQrScanProgress(chunk.total)
        }
    }

    private fun completeQrScanSession() {
        val payload = runCatching { QrChunkCodec.reassemble(qrScanChunks.values) }.getOrNull()
        resetQrScanSession()
        if (payload == null) {
            setImportError(ImportError.QrMismatch)
            return
        }
        onContainerLoaded(runCatching { backupManager.parseQrPayload(payload) }.getOrNull())
    }

    private fun publishQrScanProgress(total: Int) {
        _uiState.update {
            it.copy(
                importState = it.importState.copy(
                    scanSession = QrScanProgress(total, qrScanChunks.keys.toSet()),
                    error = null
                )
            )
        }
    }

    private fun setImportError(error: ImportError) {
        _uiState.update { it.copy(importState = it.importState.copy(error = error)) }
    }

    private fun resetQrScanSession() {
        qrScanChunks.clear()
        qrSessionHash = null
        _uiState.update { it.copy(importState = it.importState.copy(scanSession = null)) }
    }

    fun restartQrScanSession() {
        resetQrScanSession()
    }

    fun onZipPicked(openStream: () -> InputStream?) {
        viewModelScope.launch(Dispatchers.IO) {
            val container = runCatching {
                openStream()?.use { backupManager.readBackupFile(it) }?.let(backupManager::parseZip)
            }.getOrNull()
            withContext(Dispatchers.Main) { onContainerLoaded(container) }
        }
    }

    private fun onContainerLoaded(container: ByteArray?) {
        pendingImportContainer = container
        _uiState.update {
            it.copy(
                importState = if (container == null) {
                    ImportUiState(error = ImportError.NotABackup)
                } else {
                    ImportUiState(awaitingPin = true)
                }
            )
        }
    }

    fun updateImportPin(pin: String) {
        _uiState.update { it.copy(importState = it.importState.copy(pin = pin, error = null)) }
    }

    fun dismissImport() {
        pendingImportContainer = null
        qrScanChunks.clear()
        qrSessionHash = null
        _uiState.update { it.copy(importState = ImportUiState()) }
    }

    fun confirmImport() {
        val container = pendingImportContainer ?: return
        val pin = _uiState.value.importState.pin
        if (!BackupCrypto.isUsableForImport(pin) || _uiState.value.importState.isBusy) return
        flushPendingChanges()
        accountsSync.discardPending()
        _uiState.update { it.copy(importState = it.importState.copy(isBusy = true, error = null)) }
        viewModelScope.launch(Dispatchers.Default) {
            val result = runCatching {
                backupManager.restore(backupManager.decryptContainer(container, pin))
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { summary ->
                        pendingImportContainer = null
                        accountsSync.notifyExternalChange()
                        refreshScheduler.applySaved()
                        _uiState.update {
                            it.copy(
                                importState = ImportUiState(
                                    importedCount = summary.credentialCount,
                                    importedHistoryEntries = summary.historyEntries
                                )
                            )
                        }
                    },
                    onFailure = { error ->
                        val mapped = when {
                            error is BackupCryptoException -> ImportError.WrongPin
                            error is InvalidBackupException &&
                                    error.reason == InvalidBackupException.Reason.UNSUPPORTED_VERSION ->
                                ImportError.UnsupportedVersion

                            error is InvalidBackupException -> ImportError.NotABackup
                            else -> ImportError.Failed
                        }
                        _uiState.update {
                            it.copy(
                                importState = it.importState.copy(
                                    isBusy = false,
                                    error = mapped,
                                    pin = ""
                                )
                            )
                        }
                    }
                )
            }
        }
    }

    fun onImportResultShown() {
        _uiState.update { it.copy(importState = it.importState.copy(importedCount = null)) }
    }

    fun clearImportError() {
        _uiState.update { it.copy(importState = it.importState.copy(error = null)) }
    }

    private fun flushPendingChanges() {
        accountsSync.flushPending()
    }

    private fun defaultZipFileName(): String =
        "tokenusage-config-" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) +
                ".zip"
}
