package fr.gildasledrogoff.tokenusage.feature.settings.backup
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.backup.BackupCrypto
@Composable
internal fun exportErrorMessage(error: ExportError): String = when (error) {
    ExportError.PinTooShort ->
        stringResource(R.string.export_error_pin_short, BackupCrypto.MIN_PASSWORD_LENGTH)
    ExportError.PinMismatch -> stringResource(R.string.pin_mismatch)
    ExportError.QrTooLarge -> stringResource(R.string.export_error_qr_too_large)
    ExportError.Failed -> stringResource(R.string.export_error_generic)
}
@Composable
internal fun importErrorMessage(error: ImportError): String = when (error) {
    ImportError.NotABackup -> stringResource(R.string.import_error_not_backup)
    ImportError.WrongPin -> stringResource(R.string.import_error_wrong_pin)
    ImportError.UnsupportedVersion -> stringResource(R.string.import_error_version)
    ImportError.Failed -> stringResource(R.string.import_error_generic)
    ImportError.QrMismatch -> stringResource(R.string.import_error_qr_mismatch)
}
