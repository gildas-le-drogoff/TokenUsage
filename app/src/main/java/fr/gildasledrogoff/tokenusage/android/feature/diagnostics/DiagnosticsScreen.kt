package fr.gildasledrogoff.tokenusage.feature.diagnostics
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.gildasledrogoff.tokenusage.BuildConfig
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.diagnostics.AccountDiagnostic
import fr.gildasledrogoff.tokenusage.core.diagnostics.BackgroundSnapshot
import fr.gildasledrogoff.tokenusage.core.diagnostics.DiagnosticStatus
import fr.gildasledrogoff.tokenusage.core.diagnostics.DiagnosticsReport
import fr.gildasledrogoff.tokenusage.core.diagnostics.ManufacturerGroup
import fr.gildasledrogoff.tokenusage.core.diagnostics.RefreshOrigin
import fr.gildasledrogoff.tokenusage.core.diagnostics.WorkSnapshot
import fr.gildasledrogoff.tokenusage.core.workmanager.WorkManagerInitializer
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onNavigateBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.diag_report_copied)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back_rounded),
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.diag_local_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::refresh) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = null)
                    Text(stringResource(R.string.diag_action_refresh), modifier = Modifier.padding(start = 8.dp))
                }
                Button(onClick = {
                    copyToClipboard(context, viewModel.buildReport(appInfo(context)))
                    Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(painterResource(R.drawable.ic_content_copy_rounded), contentDescription = null)
                    Text(stringResource(R.string.diag_action_copy), modifier = Modifier.padding(start = 8.dp))
                }
            }
            SectionTitle(stringResource(R.string.diag_accounts_title))
            state.accounts.forEach { AccountCard(it, state.generatedAt) }
            state.background?.let { background ->
                SectionTitle(stringResource(R.string.diag_background_title))
                BackgroundCard(background, state.generatedAt)
            }
        }
    }
}
@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}
@Composable
private fun AccountCard(account: AccountDiagnostic, now: Instant) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = account.service.displayName + (account.accountLabel?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = statusLabel(account.status),
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor(account.status)
                )
            }
            account.at?.let { at ->
                Text(
                    text = stringResource(
                        R.string.diag_last_refresh,
                        relativeAge(at, now),
                        account.origin?.let { originLabel(it) } ?: "?"
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            account.error?.let {
                Text(
                    text = stringResource(R.string.diag_error_line, it.display()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (account.error == null) {
                account.lastError?.let { last ->
                    Text(
                        text = stringResource(
                            R.string.diag_last_error_line,
                            account.lastErrorAt?.let { relativeAge(it, now) } ?: "?",
                            last.display()
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
@Composable
private fun BackgroundCard(background: BackgroundSnapshot, now: Instant) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyValue(stringResource(R.string.diag_battery_ignored), yesNo(background.ignoringBatteryOptimizations))
            KeyValue(
                stringResource(R.string.diag_standby_bucket),
                BackgroundSnapshot.standbyBucketName(background.standbyBucket) ?: stringResource(R.string.diag_unknown)
            )
            if (BackgroundSnapshot.isStandbyBucketRestrictive(background.standbyBucket)) {
                Text(
                    stringResource(R.string.diag_standby_restrictive),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            KeyValue(stringResource(R.string.diag_background_restricted), yesNo(background.backgroundRestricted))
            KeyValue(stringResource(R.string.diag_doze), yesNo(background.deviceIdle))
            KeyValue(stringResource(R.string.diag_battery_saver), yesNo(background.powerSaveMode))
            background.works.forEach { work ->
                HorizontalDivider()
                WorkBlock(work, now)
            }
            background.manufacturerGroup?.let { group ->
                HorizontalDivider()
                VendorBlock(background.manufacturer, group)
            }
        }
    }
}
@Composable
private fun WorkBlock(work: WorkSnapshot, now: Instant) {
    val title = when (work.name) {
        WorkManagerInitializer.QUOTA_WORK_NAME -> stringResource(R.string.diag_work_quota)
        WorkManagerInitializer.TOKEN_WORK_NAME -> stringResource(R.string.diag_work_token)
        else -> work.name
    }
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    if (work.state == "NOT_SCHEDULED") {
        Text(stringResource(R.string.diag_work_not_scheduled), style = MaterialTheme.typography.bodySmall)
    } else {
        val attempt = if (work.runAttemptCount > 0) " · " + stringResource(R.string.diag_work_attempt, work.runAttemptCount) else ""
        Text(stringResource(R.string.diag_work_state, work.state) + attempt, style = MaterialTheme.typography.bodySmall)
    }
    work.nextRunAtEpochMs?.let {
        Text(
            stringResource(R.string.diag_work_next, relativeAge(Instant.ofEpochMilli(it), now)),
            style = MaterialTheme.typography.bodySmall
        )
    }
    val last = work.lastRun
    Text(
        text = if (last == null) {
            stringResource(R.string.diag_work_never)
        } else {
            stringResource(R.string.diag_work_last, relativeAge(Instant.ofEpochMilli(last.atEpochMs), now), last.result)
        },
        style = MaterialTheme.typography.bodySmall
    )
}
@Composable
private fun VendorBlock(manufacturer: String, group: ManufacturerGroup) {
    Text(
        stringResource(R.string.diag_vendor_title, manufacturer),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium
    )
    val instructions = when (group) {
        ManufacturerGroup.XIAOMI -> R.string.diag_vendor_xiaomi
        ManufacturerGroup.HUAWEI -> R.string.diag_vendor_huawei
        ManufacturerGroup.OPPO -> R.string.diag_vendor_oppo
        ManufacturerGroup.VIVO -> R.string.diag_vendor_vivo
        ManufacturerGroup.SAMSUNG -> R.string.diag_vendor_samsung
        ManufacturerGroup.ONEPLUS -> R.string.diag_vendor_oneplus
        ManufacturerGroup.OTHER -> R.string.diag_vendor_other
    }
    Text(stringResource(instructions), style = MaterialTheme.typography.bodySmall)
    Text(
        stringResource(R.string.diag_vendor_link, group.instructionsUrl),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
@Composable
private fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
@Composable
private fun yesNo(value: Boolean?): String = when (value) {
    true -> stringResource(R.string.diag_yes)
    false -> stringResource(R.string.diag_no)
    null -> stringResource(R.string.diag_unknown)
}
@Composable
private fun statusLabel(status: DiagnosticStatus): String = stringResource(
    when (status) {
        DiagnosticStatus.OK -> R.string.diag_status_ok
        DiagnosticStatus.ERROR -> R.string.diag_status_error
        DiagnosticStatus.NOT_CONFIGURED -> R.string.diag_status_not_configured
        DiagnosticStatus.NEVER_REFRESHED -> R.string.diag_status_never
    }
)
@Composable
private fun statusColor(status: DiagnosticStatus) = when (status) {
    DiagnosticStatus.OK -> MaterialTheme.colorScheme.primary
    DiagnosticStatus.ERROR -> MaterialTheme.colorScheme.error
    DiagnosticStatus.NOT_CONFIGURED, DiagnosticStatus.NEVER_REFRESHED -> MaterialTheme.colorScheme.onSurfaceVariant
}
@Composable
private fun originLabel(origin: RefreshOrigin): String = stringResource(
    when (origin) {
        RefreshOrigin.DASHBOARD -> R.string.diag_origin_dashboard
        RefreshOrigin.PERIODIC_WORKER -> R.string.diag_origin_worker
        RefreshOrigin.MANUAL_SERVICE -> R.string.diag_origin_manual
        RefreshOrigin.TILE -> R.string.diag_origin_tile
    }
)
private fun fr.gildasledrogoff.tokenusage.core.diagnostics.DiagnosticError.display(): String =
    if (message.isBlank()) type else "$type — $message"

private fun relativeAge(at: Instant, now: Instant): String =
    DateUtils.getRelativeTimeSpanString(
        at.toEpochMilli(),
        now.toEpochMilli(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    ).toString()
private fun appInfo(context: Context): DiagnosticsReport.AppInfo {
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    return DiagnosticsReport.AppInfo(
        versionName = info?.versionName ?: "?",
        versionCode = info?.longVersionCode ?: 0L,
        gitHash = BuildConfig.GIT_HASH,
        gitDate = BuildConfig.GIT_DATE
    )
}
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("TokenUsage diagnostics", text))
}
