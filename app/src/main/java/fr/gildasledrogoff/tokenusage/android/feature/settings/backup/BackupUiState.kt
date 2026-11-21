package fr.gildasledrogoff.tokenusage.feature.settings.backup

data class BackupUiState(
    val export: ExportUiState = ExportUiState(),
    val importState: ImportUiState = ImportUiState()
)

sealed interface ImportError {
    data object NotABackup : ImportError
    data object WrongPin : ImportError
    data object UnsupportedVersion : ImportError
    data object Failed : ImportError
    data object QrMismatch : ImportError
}

data class QrScanProgress(
    val total: Int,
    val received: Set<Int>
) {
    val nextIndex: Int get() = (1..total).firstOrNull { it !in received } ?: total
}

data class ImportUiState(
    val awaitingPin: Boolean = false,
    val pin: String = "",
    val isBusy: Boolean = false,
    val error: ImportError? = null,
    val scanSession: QrScanProgress? = null,
    val importedCount: Int? = null,
    val importedHistoryEntries: Int = 0
)

sealed interface ExportError {
    data object PinTooShort : ExportError
    data object PinMismatch : ExportError
    data object QrTooLarge : ExportError
    data object Failed : ExportError
}

data class ExportUiState(
    val pin: String = "",
    val confirmPin: String = "",
    val isBusy: Boolean = false,
    val error: ExportError? = null,
    val zipReady: Boolean = false,
    val zipFileName: String = "",
    val qrCodes: List<String>? = null,
    val qrIndex: Int = 0
)
