package fr.gildasledrogoff.tokenusage.feature.settings

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.gildasledrogoff.tokenusage.core.domain.model.LockScreenVisibility
import fr.gildasledrogoff.tokenusage.core.util.BatteryOptimizationHelper
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.ui.components.ChoiceChipRow
import fr.gildasledrogoff.tokenusage.core.backup.BackupCrypto
import fr.gildasledrogoff.tokenusage.core.backup.PasswordStrength
import fr.gildasledrogoff.tokenusage.core.backup.PasswordStrengthEstimator
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.util.ServiceIcon
import fr.gildasledrogoff.tokenusage.core.util.iconRes
import fr.gildasledrogoff.tokenusage.ui.i18n.appErrorMessage
import fr.gildasledrogoff.tokenusage.ui.security.BiometricGate
import fr.gildasledrogoff.tokenusage.ui.theme.WidthClass
import fr.gildasledrogoff.tokenusage.ui.theme.dimens
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToCredits: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val backupWriteFailed = stringResource(R.string.toast_backup_write_failed)

    val biometricUnavailable = stringResource(R.string.app_lock_unavailable)
    val biometricConfirmTitle = stringResource(R.string.app_lock_biometric_confirm_title)
    val biometricConfirmSubtitle = stringResource(R.string.app_lock_biometric_confirm_subtitle)
    val confirmAppLockWithBiometric: (() -> Unit) -> Unit = { onConfirmed ->
        val activity = context as? FragmentActivity
        if (activity == null || BiometricGate.availability(activity) != BiometricGate.Availability.READY) {
            Toast.makeText(context, biometricUnavailable, Toast.LENGTH_LONG).show()
        } else {
            BiometricGate.authenticate(
                activity = activity,
                title = biometricConfirmTitle,
                subtitle = biometricConfirmSubtitle,
                onSuccess = onConfirmed,
                onError = {  }
            )
        }
    }

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

    val scanPrompt = stringResource(R.string.import_scan_prompt)
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
                pluralStringResource(R.plurals.import_history_added, importedHistory, importedHistory)
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

    var batteryExempt by remember { mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) }
    LifecycleResumeEffect(Unit) {
        batteryExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }
    val launchBatteryIntent: (android.content.Intent) -> Unit = { intent ->
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(BatteryOptimizationHelper.openBatteryOptimizationSettingsIntent()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(painter = painterResource(R.drawable.ic_arrow_back_rounded), contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        val contentWidthModifier = if (MaterialTheme.dimens.widthClass != WidthClass.COMPACT) {
            Modifier.widthIn(max = SettingsContentMaxWidth)
        } else {
            Modifier.fillMaxWidth()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .then(contentWidthModifier)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            DisplaySettingsSection(
                themeMode = uiState.themeMode,
                quotaDisplayMode = uiState.quotaDisplayMode,
                onThemeModeChange = { viewModel.setThemeMode(it) },
                onQuotaDisplayModeChange = { viewModel.setQuotaDisplayMode(it) }
            )

            CurrencySettingsSection(
                state = uiState.currencyConversion,
                onDisplayCurrencyChange = { viewModel.setDisplayCurrency(it) },
                onSourceChange = { viewModel.setFxRateSource(it) },
                onApiFetchAllowedChange = { viewModel.setFxApiFetchAllowed(it) },
                onManualUsdEurChange = { viewModel.updateManualUsdEurRate(it) },
                onManualUsdCnyChange = { viewModel.updateManualUsdCnyRate(it) },
                onRefreshRates = { viewModel.refreshFxRates() }
            )

            NotificationsSection(
                enabled = uiState.notificationsEnabled,
                onToggle = { viewModel.setNotificationsEnabled(it) }
            )

            // Security: app lock (biometric / device credential) + lock screen exposure
            SecuritySection(
                lockScreenVisibility = uiState.lockScreenVisibility,
                onLockScreenVisibilityChange = { viewModel.setLockScreenVisibility(it) },
                appLockEnabled = uiState.appLockEnabled,
                onToggle = { want ->
                    if (!want) {
                        viewModel.setAppLockEnabled(false)
                    } else {
                        confirmAppLockWithBiometric {
                            viewModel.setAppLockEnabled(true)
                        }
                    }
                }
            )

            // Debug builds only: sample data for UI/UX validation
            if (uiState.demoDataAvailable) {
                ToggleSection(
                    title = stringResource(R.string.demo_data_title),
                    subtitle = stringResource(R.string.demo_data_subtitle),
                    checked = uiState.demoDataEnabled,
                    onToggle = { viewModel.setDemoDataEnabled(it) }
                )
            }

            AiService.entries.forEach { service ->
                if (service == AiService.CLAUDE) {
                    ClaudeAccountsSection(
                        accounts = uiState.claudeAccounts,
                        onAddAccount = { viewModel.addClaudeAccount() },
                        onRemoveAccount = { viewModel.removeClaudeAccount(it) },
                        onFieldChange = { accountId, field, value ->
                            viewModel.updateClaudeAccount(accountId, field, value)
                        },
                        onValidate = { viewModel.validateClaudeAccount(it) },
                        onSessionKeyCaptured = { accountId, key -> viewModel.applyCapturedSessionKey(accountId, key) }
                    )
                } else if (service == AiService.DEEPSEEK) {
                    DeepseekAccountsSection(
                        accounts = uiState.deepseekAccounts,
                        onAddAccount = { viewModel.addDeepseekAccount() },
                        onRemoveAccount = { viewModel.removeDeepseekAccount(it) },
                        onFieldChange = { accountId, field, value ->
                            viewModel.updateDeepseekAccount(accountId, field, value)
                        },
                        onValidate = { viewModel.validateDeepseekAccount(it) }
                    )
                } else if (service == AiService.ZAI) {
                    ZaiAccountsSection(
                        accounts = uiState.zaiAccounts,
                        onAddAccount = { viewModel.addZaiAccount() },
                        onRemoveAccount = { viewModel.removeZaiAccount(it) },
                        onFieldChange = { accountId, field, value ->
                            viewModel.updateZaiAccount(accountId, field, value)
                        },
                        onValidate = { viewModel.validateZaiAccount(it) },
                        hideMonthlyTools = uiState.zaiHideMonthlyTools,
                        onHideMonthlyToolsChange = { viewModel.setZaiHideMonthlyTools(it) }
                    )
                } else if (service.usesSingleApiKey) {
                    ApiKeyServiceSection(
                        service = service,
                        state = uiState.serviceStates[service] ?: ServiceCredentialState(),
                        onKeyChange = { viewModel.updateField(service, "accessToken", it) },
                        onValidate = { viewModel.validateCredential(service) },
                        onRemove = { viewModel.removeCredential(service) },
                        onGitHubSignIn = { viewModel.startCopilotLogin() }
                    )
                } else {
                    val state = uiState.serviceStates[service] ?: ServiceCredentialState()
                    ServiceCredentialSection(
                        service = service,
                        state = state,
                        onFieldChange = { field, value -> viewModel.updateField(service, field, value) },
                        onValidate = { viewModel.validateCredential(service) },
                        onRemove = { viewModel.removeCredential(service) }
                    )
                }
            }

            RefreshIntervalSection(
                currentMinutes = uiState.refreshIntervalMinutes,
                onIntervalChange = { viewModel.setRefreshInterval(it) },
                batteryExempt = batteryExempt,
                onRequestExemption = {
                    viewModel.onBatteryChoiceMade()
                    launchBatteryIntent(BatteryOptimizationHelper.requestIgnoreBatteryOptimizationsIntent(context))
                },
                onOpenBatterySettings = {
                    viewModel.onBatteryChoiceMade()
                    launchBatteryIntent(BatteryOptimizationHelper.openBatteryOptimizationSettingsIntent())
                }
            )

            BackupSection(
                export = uiState.export,
                onPinChange = { viewModel.updateExportPin(it) },
                onConfirmPinChange = { viewModel.updateExportConfirmPin(it) },
                onExportZip = { viewModel.prepareZipExport() },
                onShowQr = { viewModel.prepareQrExport() },
                importError = uiState.importState.error.takeUnless { uiState.importState.awaitingPin },
                onScanQr = launchQrScan,
                onPickZip = launchZipPick
            )

            CreditsEntry(onClick = onNavigateToCredits)

            DangerZoneSection(
                onDeleteAll = { viewModel.showDeleteConfirmDialog() }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
        }
    }

    if (uiState.showDeleteConfirmDialog) {
        DeleteConfirmDialog(
            onConfirm = {
                viewModel.deleteAllCredentials()
                viewModel.dismissDeleteConfirmDialog()
            },
            onDismiss = { viewModel.dismissDeleteConfirmDialog() }
        )
    }

    uiState.export.qrPayload?.let { payload ->
        ExportQrDialog(
            payload = payload,
            onDismiss = { viewModel.dismissQrExport() }
        )
    }

    uiState.copilotLogin?.let { login ->
        CopilotLoginDialog(
            state = login,
            onRetry = { viewModel.startCopilotLogin() },
            onDismiss = { viewModel.cancelCopilotLogin() }
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

@Composable
private fun ServiceCredentialSection(
    service: AiService,
    state: ServiceCredentialState,
    onFieldChange: (String, String) -> Unit,
    onValidate: () -> Unit,
    onRemove: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val configured = state.isConfigured()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ExpandableSectionHeader(
                iconRes = service.iconRes,
                iconContentDescription = service.displayName,
                title = service.displayName,
                statusLabel = if (configured) stringResource(R.string.status_in_use) else stringResource(R.string.status_not_in_use),
                configured = configured,
                expanded = expanded,
                onToggle = { expanded = !expanded }
            )

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    SettingsFields(
                        { modifier ->
                            OutlinedTextField(
                                value = state.accessToken,
                                onValueChange = { onFieldChange("accessToken", it) },
                                label = { Text(stringResource(R.string.field_access_token)) },
                                supportingText = {
                                    Text(
                                        stringResource(
                                            if (state.isStored && state.accessToken.isBlank()) {
                                                R.string.stored_credential_hint
                                            } else {
                                                R.string.field_secret_replace_hint
                                            }
                                        )
                                    )
                                },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = modifier,
                                singleLine = true
                            )
                        },
                        { modifier ->
                            OutlinedTextField(
                                value = state.refreshToken,
                                onValueChange = { onFieldChange("refreshToken", it) },
                                label = { Text(stringResource(R.string.field_refresh_token)) },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = modifier,
                                singleLine = true
                            )
                        }
                    )

                    when (service) {
                        AiService.CODEX -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            SettingsFields(
                                { modifier ->
                                    OutlinedTextField(
                                        value = state.accountId,
                                        onValueChange = { onFieldChange("accountId", it) },
                                        label = { Text(stringResource(R.string.field_account_id_optional)) },
                                        modifier = modifier,
                                        singleLine = true
                                    )
                                }
                            )
                        }
                        AiService.GEMINI -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            SettingsFields(
                                { modifier ->
                                    OutlinedTextField(
                                        value = state.oauthClientId,
                                        onValueChange = { onFieldChange("oauthClientId", it) },
                                        label = { Text(stringResource(R.string.field_oauth_client_id)) },
                                        supportingText = { Text(stringResource(R.string.field_oauth_client_id_hint)) },
                                        modifier = modifier,
                                        singleLine = true
                                    )
                                },
                                { modifier ->
                                    OutlinedTextField(
                                        value = state.oauthClientSecret,
                                        onValueChange = { onFieldChange("oauthClientSecret", it) },
                                        label = { Text(stringResource(R.string.field_oauth_client_secret)) },
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = modifier,
                                        singleLine = true
                                    )
                                }
                            )
                            if (state.expiresAtDisplay.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                SettingsFields(
                                    { modifier ->
                                        OutlinedTextField(
                                            value = state.expiresAtDisplay,
                                            onValueChange = {},
                                            label = { Text(stringResource(R.string.field_token_expiry)) },
                                            modifier = modifier,
                                            readOnly = true,
                                            enabled = false,
                                            singleLine = true
                                        )
                                    }
                                )
                            }
                        }
                        else -> {}
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = onValidate,
                            enabled = !state.isValidating && canValidate(state)
                        ) {
                            if (state.isValidating) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.action_validate))
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        when (state.validationResult) {
                            is ValidationResult.Success -> {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check_circle_rounded),
                                    contentDescription = stringResource(R.string.cd_valid),
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.status_valid),
                                    color = Color(0xFF4CAF50),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            is ValidationResult.Failure -> {
                                Icon(
                                    painter = painterResource(R.drawable.ic_error_rounded),
                                    contentDescription = stringResource(R.string.cd_invalid),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    appErrorMessage(state.validationResult.error),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            null -> {}
                        }

                        if (state.isStored) {
                            Spacer(modifier = Modifier.width(12.dp))
                            ConfirmDeleteIconButton(
    contentDescription = stringResource(R.string.cd_remove_credential),
    onConfirmed = onRemove
)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeepseekAccountsSection(
    accounts: List<DeepseekAccountUi>,
    onAddAccount: () -> Unit,
    onRemoveAccount: (String) -> Unit,
    onFieldChange: (String, String, String) -> Unit,
    onValidate: (String) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val configuredCount = accounts.count { it.hasStoredKey || it.apiKey.isNotBlank() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ExpandableSectionHeader(
                iconRes = AiService.DEEPSEEK.iconRes,
                iconContentDescription = AiService.DEEPSEEK.displayName,
                title = AiService.DEEPSEEK.displayName,
                statusLabel = accountStatus(configuredCount),
                configured = configuredCount > 0,
                expanded = expanded,
                onToggle = { expanded = !expanded }
            )

            AnimatedVisibility(visible = expanded) {
                Column {
                    Text(
                        text = stringResource(R.string.deepseek_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (accounts.isEmpty()) {
                        Text(
                            text = stringResource(R.string.deepseek_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    accounts.forEachIndexed { index, account ->
                        if (index > 0) Spacer(modifier = Modifier.height(16.dp))
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.account_n, index + 1),
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                ConfirmDeleteIconButton(
    contentDescription = stringResource(R.string.cd_remove_account),
    onConfirmed = { onRemoveAccount(account.id) }
)
                            }

                            SettingsFields(
                                { modifier ->
                                    OutlinedTextField(
                                        value = account.label,
                                        onValueChange = { onFieldChange(account.id, "label", it) },
                                        label = { Text(stringResource(R.string.field_label_optional)) },
                                        modifier = modifier,
                                        singleLine = true
                                    )
                                },
                                { modifier ->
                                    OutlinedTextField(
                                        value = account.apiKey,
                                        onValueChange = { onFieldChange(account.id, "apiKey", it) },
                                        label = { Text(stringResource(R.string.field_api_key)) },
                                        supportingText = {
                                            Text(
                                                stringResource(
                                                    if (account.hasStoredKey && account.apiKey.isBlank()) {
                                                        R.string.stored_credential_hint
                                                    } else {
                                                        R.string.field_api_key_format
                                                    }
                                                )
                                            )
                                        },
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = modifier,
                                        singleLine = true
                                    )
                                }
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = { onValidate(account.id) },
                                    enabled = !account.isValidating &&
                                        (account.apiKey.isNotBlank() || account.hasStoredKey)
                                ) {
                                    if (account.isValidating) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(stringResource(R.string.action_validate))
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                when (account.validationResult) {
                                    is ValidationResult.Success -> {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_check_circle_rounded),
                                            contentDescription = stringResource(R.string.cd_valid),
                                            tint = Color(0xFF4CAF50),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            stringResource(R.string.status_valid),
                                            color = Color(0xFF4CAF50),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    is ValidationResult.Failure -> {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_error_rounded),
                                            contentDescription = stringResource(R.string.cd_invalid),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            appErrorMessage(account.validationResult.error),
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    null -> {}
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(onClick = onAddAccount) {
                        Icon(painter = painterResource(R.drawable.ic_add_rounded), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_add_account))
                    }
                }
            }
        }
    }
}

private fun canValidate(state: ServiceCredentialState): Boolean =
    state.isStored || state.accessToken.isNotBlank()

private fun ServiceCredentialState.isConfigured(): Boolean =
    isStored || accessToken.isNotBlank()

@Composable
private fun ZaiAccountsSection(
    accounts: List<ZaiAccountUi>,
    onAddAccount: () -> Unit,
    onRemoveAccount: (String) -> Unit,
    onFieldChange: (String, String, String) -> Unit,
    onValidate: (String) -> Unit,
    hideMonthlyTools: Boolean,
    onHideMonthlyToolsChange: (Boolean) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val configuredCount = accounts.count { it.hasStoredKey || it.apiKey.isNotBlank() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ExpandableSectionHeader(
                iconRes = AiService.ZAI.iconRes,
                iconContentDescription = AiService.ZAI.displayName,
                title = AiService.ZAI.displayName,
                statusLabel = accountStatus(configuredCount),
                configured = configuredCount > 0,
                expanded = expanded,
                onToggle = { expanded = !expanded }
            )

            AnimatedVisibility(visible = expanded) {
                Column {
                    Text(
                        text = stringResource(
                            if (hideMonthlyTools) R.string.zai_hint_no_tools else R.string.zai_hint
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .toggleable(
                                value = hideMonthlyTools,
                                role = Role.Checkbox,
                                onValueChange = onHideMonthlyToolsChange
                            )
                    ) {
                        Checkbox(checked = hideMonthlyTools, onCheckedChange = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.zai_hide_monthly_tools),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (accounts.isEmpty()) {
                        Text(
                            text = stringResource(R.string.zai_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    accounts.forEachIndexed { index, account ->
                        if (index > 0) Spacer(modifier = Modifier.height(16.dp))
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.account_n, index + 1),
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                ConfirmDeleteIconButton(
    contentDescription = stringResource(R.string.cd_remove_account),
    onConfirmed = { onRemoveAccount(account.id) }
)
                            }

                            SettingsFields(
                                { modifier ->
                                    OutlinedTextField(
                                        value = account.label,
                                        onValueChange = { onFieldChange(account.id, "label", it) },
                                        label = { Text(stringResource(R.string.field_label_optional)) },
                                        modifier = modifier,
                                        singleLine = true
                                    )
                                },
                                { modifier ->
                                    OutlinedTextField(
                                        value = account.apiKey,
                                        onValueChange = { onFieldChange(account.id, "apiKey", it) },
                                        label = { Text(stringResource(R.string.field_api_key)) },
                                        supportingText = {
                                            Text(
                                                stringResource(
                                                    if (account.hasStoredKey && account.apiKey.isBlank()) {
                                                        R.string.stored_credential_hint
                                                    } else {
                                                        R.string.field_api_key_zai_hint
                                                    }
                                                )
                                            )
                                        },
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = modifier,
                                        singleLine = true
                                    )
                                }
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = { onValidate(account.id) },
                                    enabled = !account.isValidating &&
                                        (account.apiKey.isNotBlank() || account.hasStoredKey)
                                ) {
                                    if (account.isValidating) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(stringResource(R.string.action_validate))
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                when (account.validationResult) {
                                    is ValidationResult.Success -> {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_check_circle_rounded),
                                            contentDescription = stringResource(R.string.cd_valid),
                                            tint = Color(0xFF4CAF50),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            stringResource(R.string.status_valid),
                                            color = Color(0xFF4CAF50),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    is ValidationResult.Failure -> {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_error_rounded),
                                            contentDescription = stringResource(R.string.cd_invalid),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            appErrorMessage(account.validationResult.error),
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    null -> {}
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(onClick = onAddAccount) {
                        Icon(painter = painterResource(R.drawable.ic_add_rounded), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_add_account))
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupSection(
    export: ExportUiState,
    onPinChange: (String) -> Unit,
    onConfirmPinChange: (String) -> Unit,
    onExportZip: () -> Unit,
    onShowQr: () -> Unit,
    importError: ImportError?,
    onScanQr: () -> Unit,
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

@Composable
private fun ImportPinDialog(
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
                OutlinedTextField(
                    value = state.pin,
                    onValueChange = onPinChange,
                    label = { Text(stringResource(R.string.field_import_pin)) },
                    isError = state.error != null,
                    supportingText = state.error?.let { error ->
                        { Text(importErrorMessage(error), color = MaterialTheme.colorScheme.error) }
                    },
                    enabled = !state.isBusy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
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
private fun ExportQrDialog(
    payload: String,
    onDismiss: () -> Unit
) {
    val qrImage = rememberQrImage(payload, QR_SIZE_PX)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qr_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.qr_dialog_hint),
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
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

/** Renders the QR code off the main thread; null until ready. */
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


private val SettingsContentMaxWidth = 720.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshIntervalSection(
    currentMinutes: Long,
    onIntervalChange: (Long) -> Unit,
    batteryExempt: Boolean,
    onRequestExemption: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.refresh_interval_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

            val options = listOf(
                15L to stringResource(R.string.refresh_15m),
                30L to stringResource(R.string.refresh_30m),
                60L to stringResource(R.string.refresh_1h),
                0L to stringResource(R.string.refresh_manual)
            )
            ChoiceChipRow(
                options = options,
                selected = options.firstOrNull { it.first == currentMinutes } ?: options[1],
                label = { it.second },
                onSelect = { onIntervalChange(it.first) },
                modifier = Modifier.fillMaxWidth()
            )

            BackgroundRefreshBlock(
                manualOnly = currentMinutes == 0L,
                batteryExempt = batteryExempt,
                onRequestExemption = onRequestExemption,
                onOpenBatterySettings = onOpenBatterySettings
            )
        }
    }
}

/**
 * Battery-optimization exemption, left to the user. Without it Android
 * (Doze, App Standby buckets, OEM task killers) may defer or skip the
 * periodic refresh, so the notification, widgets and tile can show stale
 * quotas: the warning says so explicitly. Granting it only makes the
 * scheduled refresh reliable — the interval chosen above stays the cadence.
 */
@Composable
private fun BackgroundRefreshBlock(
    manualOnly: Boolean,
    batteryExempt: Boolean,
    onRequestExemption: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    Column(
        modifier = Modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.battery_section_title),
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = stringResource(
                if (batteryExempt) R.string.battery_status_exempt else R.string.battery_status_optimized
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        if (!batteryExempt && !manualOnly) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_error_rounded),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.battery_warning_optimized),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else {
            Text(
                text = stringResource(
                    when {
                        manualOnly -> R.string.battery_hint_manual
                        else -> R.string.battery_hint_exempt
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!batteryExempt) {
                OutlinedButton(onClick = onRequestExemption) {
                    Text(stringResource(R.string.battery_action_exempt))
                }
            }
            TextButton(onClick = onOpenBatterySettings) {
                Text(stringResource(R.string.battery_action_system_settings))
            }
        }
    }
}

@Composable
private fun NotificationsSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    ToggleSection(
        title = stringResource(R.string.notifications_title),
        subtitle = stringResource(R.string.notifications_subtitle),
        checked = enabled,
        onToggle = onToggle
    )
}

/** Security settings: app lock with biometric / device-credential gate. */
@Composable
private fun SecuritySection(
    lockScreenVisibility: LockScreenVisibility,
    onLockScreenVisibilityChange: (LockScreenVisibility) -> Unit,
    appLockEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock_rounded),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.security_title),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_lock_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.app_lock_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = appLockEnabled,
                    onCheckedChange = onToggle
                )
            }

            Text(
                text = stringResource(R.string.lock_screen_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            val visibilityOptions = LockScreenVisibility.entries
            ChoiceChipRow(
                options = visibilityOptions,
                selected = lockScreenVisibility,
                label = { stringResource(lockScreenVisibilityLabelRes(it)) },
                onSelect = onLockScreenVisibilityChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
            Text(
                text = stringResource(lockScreenVisibilityHintRes(lockScreenVisibility)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

private fun lockScreenVisibilityLabelRes(visibility: LockScreenVisibility): Int = when (visibility) {
    LockScreenVisibility.SHOW_ALL -> R.string.lock_screen_show_all
    LockScreenVisibility.SYSTEM -> R.string.lock_screen_system
    LockScreenVisibility.HIDDEN -> R.string.lock_screen_hidden
}

private fun lockScreenVisibilityHintRes(visibility: LockScreenVisibility): Int = when (visibility) {
    LockScreenVisibility.SHOW_ALL -> R.string.lock_screen_hint_show_all
    LockScreenVisibility.SYSTEM -> R.string.lock_screen_hint_system
    LockScreenVisibility.HIDDEN -> R.string.lock_screen_hint_hidden
}

@Composable
private fun ToggleSection(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun CreditsEntry(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.ic_lama),
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_credits_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.settings_credits_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DangerZoneSection(onDeleteAll: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.danger_zone_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onDeleteAll,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(painter = painterResource(R.drawable.ic_delete_forever_rounded), contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_delete_all))
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_delete_all)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.delete_dialog_text))
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = confirmText,
                    onValueChange = { confirmText = it },
                    label = { Text(stringResource(R.string.field_type_delete)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmText == "DELETE"
            ) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * A credential can be validated when it carries enough data for the service.
 * Claude accepts either OAuth tokens or a web session key (sk-ant-sid01-/02-…).
 */
/**
 * Single static key services (Copilot, OpenRouter, OpenAI): one secret
 * field, a short hint, and for Copilot a GitHub sign-in that fills it.
 */
@Composable
private fun ApiKeyServiceSection(
    service: AiService,
    state: ServiceCredentialState,
    onKeyChange: (String) -> Unit,
    onValidate: () -> Unit,
    onRemove: () -> Unit,
    onGitHubSignIn: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val configured = state.isStored || state.accessToken.isNotBlank()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ExpandableSectionHeader(
                iconRes = service.iconRes,
                iconContentDescription = service.displayName,
                title = service.displayName,
                statusLabel = stringResource(if (configured) R.string.status_in_use else R.string.status_not_in_use),
                configured = configured,
                expanded = expanded,
                onToggle = { expanded = !expanded }
            )

            AnimatedVisibility(visible = expanded) {
                Column {
                    Text(
                        text = stringResource(apiKeyHintRes(service)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    if (service == AiService.COPILOT) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onGitHubSignIn, modifier = Modifier.fillMaxWidth()) {
                            Icon(painter = painterResource(R.drawable.ic_login_rounded), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.copilot_sign_in))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = state.accessToken,
                        onValueChange = onKeyChange,
                        label = { Text(stringResource(apiKeyLabelRes(service))) },
                        supportingText = {
                            Text(
                                stringResource(
                                    if (state.isStored && state.accessToken.isBlank()) {
                                        R.string.stored_credential_hint
                                    } else {
                                        apiKeyFieldHintRes(service)
                                    }
                                )
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = onValidate,
                            enabled = !state.isValidating && configured
                        ) {
                            if (state.isValidating) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.action_validate))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        ValidationResultLabel(state.validationResult, modifier = Modifier.weight(1f))
                        if (state.isStored) {
                            ConfirmDeleteIconButton(
    contentDescription = stringResource(R.string.cd_remove_credential),
    onConfirmed = onRemove
)
                        }
                    }
                }
            }
        }
    }
}

@StringRes
private fun apiKeyHintRes(service: AiService): Int = when (service) {
    AiService.COPILOT -> R.string.copilot_hint
    AiService.OPENROUTER -> R.string.openrouter_hint
    else -> R.string.openai_hint
}

@StringRes
private fun apiKeyLabelRes(service: AiService): Int = when (service) {
    AiService.COPILOT -> R.string.field_github_token
    AiService.OPENAI -> R.string.field_openai_admin_key
    else -> R.string.field_api_key
}

@StringRes
private fun apiKeyFieldHintRes(service: AiService): Int = when (service) {
    AiService.COPILOT -> R.string.field_github_token_hint
    AiService.OPENAI -> R.string.field_openai_admin_key_hint
    else -> R.string.field_openrouter_key_hint
}

/** "Valid" in green, or the localized error in red; nothing before a validation. */
@Composable
internal fun ValidationResultLabel(result: ValidationResult?, modifier: Modifier = Modifier) {
    val valid = Color(0xFF4CAF50)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        when (result) {
            is ValidationResult.Success -> {
                Icon(
                    painter = painterResource(R.drawable.ic_check_circle_rounded),
                    contentDescription = stringResource(R.string.cd_valid),
                    tint = valid,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.status_valid), color = valid, style = MaterialTheme.typography.bodySmall)
            }
            is ValidationResult.Failure -> {
                Icon(
                    painter = painterResource(R.drawable.ic_error_rounded),
                    contentDescription = stringResource(R.string.cd_invalid),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    appErrorMessage(result.error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            null -> {}
        }
    }
}

/**
 * GitHub device flow: shows the one-time code, copies it and opens
 * github.com/login/device; the ViewModel polls until the user approves.
 */
@Composable
private fun CopilotLoginDialog(
    state: CopilotLoginUi,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val clipLabel = stringResource(R.string.clip_github_code)
    val scope = rememberCoroutineScope()
    val copyCode = {
        scope.launch {
            val clip = ClipData.newPlainText(clipLabel, state.userCode)
            // Keep the device code out of the Android 13+ clipboard preview.
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
            clipboard.setClipEntry(ClipEntry(clip))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.copilot_login_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    state.error != null -> Text(
                        text = copilotLoginErrorText(state.error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    state.userCode.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.copilot_login_requesting))
                    }
                    else -> {
                        Text(stringResource(R.string.copilot_login_step_code))
                        Text(
                            text = state.userCode,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        OutlinedButton(onClick = { copyCode() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(painter = painterResource(R.drawable.ic_content_copy_rounded), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.copilot_login_copy))
                        }
                        Button(
                            onClick = {
                                copyCode()
                                uriHandler.openUri(state.verificationUri.ifBlank { GITHUB_DEVICE_URL })
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(painter = painterResource(R.drawable.ic_open_in_new_rounded), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.copilot_login_open))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.copilot_login_waiting),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.error != null) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun copilotLoginErrorText(error: AppError): String = when {
    error is AppError.AuthError && error.message == "denied" -> stringResource(R.string.copilot_login_denied)
    error is AppError.AuthError && error.message == "expired" -> stringResource(R.string.copilot_login_expired)
    else -> appErrorMessage(error)
}

private const val GITHUB_DEVICE_URL = "https://github.com/login/device"

/**
 * Multi-account services (Claude, DeepSeek, Z.ai) are "in use" when at least one
 * account carries an API key; [accountStatus] turns the count into a label.
 */
@Composable
internal fun accountStatus(count: Int): String = when (count) {
    0 -> stringResource(R.string.status_not_in_use)
    else -> pluralStringResource(R.plurals.accounts_in_use, count, count)
}

/**
 * Localized text for a semantic export failure ([ExportError] is produced by
 * the ViewModel without any string so it stays locale-independent).
 */
@Composable
private fun exportErrorMessage(error: ExportError): String = when (error) {
    ExportError.PinTooShort ->
        stringResource(R.string.export_error_pin_short, BackupCrypto.MIN_PASSWORD_LENGTH)
    ExportError.PinMismatch -> stringResource(R.string.pin_mismatch)
    ExportError.QrTooLarge -> stringResource(R.string.export_error_qr_too_large)
    ExportError.Failed -> stringResource(R.string.export_error_generic)
}

@Composable
private fun importErrorMessage(error: ImportError): String = when (error) {
    ImportError.NotABackup -> stringResource(R.string.import_error_not_backup)
    ImportError.WrongPin -> stringResource(R.string.import_error_wrong_pin)
    ImportError.UnsupportedVersion -> stringResource(R.string.import_error_version)
    ImportError.Failed -> stringResource(R.string.import_error_generic)
}

/**
 * Clickable card header for a collapsible configuration section. Always visible
 * whether collapsed or expanded, it shows the service icon, its name, an
 * in-use indicator (green check when configured, greyed-out check otherwise)
 * and an expand chevron.
 */
@Composable
internal fun ExpandableSectionHeader(
    iconRes: Int,
    iconContentDescription: String,
    title: String,
    statusLabel: String,
    configured: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "expandChevron"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = if (expanded) {
                    stringResource(R.string.cd_collapse)
                } else {
                    stringResource(R.string.cd_expand)
                },
                onClick = onToggle
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ServiceIcon(
            iconRes = iconRes,
            contentDescription = iconContentDescription,
            size = 24.dp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_check_circle_rounded),
            contentDescription = stringResource(
                if (configured) R.string.status_in_use else R.string.status_not_in_use
            ),
            tint = if (configured) {
                Color(0xFF4CAF50)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            painter = painterResource(R.drawable.ic_expand_more_rounded),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(24.dp)
                .rotate(chevronRotation)
        )
    }
}
