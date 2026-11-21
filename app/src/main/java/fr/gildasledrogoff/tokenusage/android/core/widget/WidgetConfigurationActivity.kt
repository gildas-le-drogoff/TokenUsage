package fr.gildasledrogoff.tokenusage.core.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.security.IntegrityGuard
import fr.gildasledrogoff.tokenusage.core.util.ServiceIcon
import fr.gildasledrogoff.tokenusage.core.util.iconRes
import fr.gildasledrogoff.tokenusage.core.workmanager.QuotaRefreshService
import fr.gildasledrogoff.tokenusage.ui.security.CompromisedDeviceScreen
import fr.gildasledrogoff.tokenusage.ui.theme.TokenUsageTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class WidgetConfigurationActivity : ComponentActivity() {
    @Inject
    lateinit var encryptedPrefsManager: EncryptedPrefsManager

    @Inject
    lateinit var widgetPrefsManager: WidgetPrefsManager

    @Inject
    lateinit var demoData: DemoDataRepository

    @Inject
    lateinit var integrityGuard: IntegrityGuard

    @Inject
    lateinit var displayPreferences: DisplayPreferencesRepository

    @Inject
    lateinit var quotaRefreshService: QuotaRefreshService
    private val appWidgetId: Int by lazy {
        intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }
    private val widgetClassName: String? by lazy {
        AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className
    }
    private val isCompactWidget: Boolean
        get() = widgetClassName == QuotaCompactWidgetReceiver::class.java.name
    private val isMainQuotaWidget: Boolean
        get() = widgetClassName == QuotaWidgetReceiver::class.java.name

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        enableEdgeToEdge()
        if (integrityGuard.isVaultLocked) {
            setContent {
                AppTheme {
                    CompromisedDeviceScreen(
                        threats = integrityGuard.threats,
                        onClose = ::finish
                    )
                }
            }
            return
        }
        setContent {
            AppTheme {
                val availableServices = if (demoData.isEnabled()) {
                    demoData.supportedServices
                } else {
                    AiService.entries.filter { encryptedPrefsManager.hasCredential(it) }.toSet()
                }
                val checkedState = remember {
                    mutableStateMapOf<AiService, Boolean>().apply {
                        if (isCompactWidget) {
                            availableServices.firstOrNull()?.let { this[it] = true }
                        } else {
                            availableServices.forEach { this[it] = true }
                        }
                    }
                }
                val anyChecked = checkedState.values.any { it }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.widget_config_title)) }
                        )
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp)
                    ) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.widget_config_instruction),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val hintRes = when {
                            isCompactWidget -> R.string.widget_config_compact_hint
                            isMainQuotaWidget -> R.string.widget_config_main_hint
                            else -> null
                        }
                        if (hintRes != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(hintRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        if (availableServices.isEmpty()) {
                            Text(
                                text = stringResource(R.string.widget_config_no_services),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            for (service in AiService.entries) {
                                val hasCredential = service in availableServices
                                ServiceCheckRow(
                                    service = service,
                                    checked = checkedState[service] ?: false,
                                    enabled = hasCredential,
                                    onCheckedChange = { checked ->
                                        if (isCompactWidget && checked) {
                                            availableServices.forEach { checkedState[it] = false }
                                        }
                                        checkedState[service] = checked
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 24.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    setResult(RESULT_CANCELED)
                                    finish()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.action_cancel))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Button(
                                onClick = { confirmSelection(checkedState) },
                                enabled = anyChecked,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.action_confirm))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun confirmSelection(checkedState: Map<AiService, Boolean>) {
        val selectedServices = checkedState
            .filter { it.value }
            .keys
        widgetPrefsManager.saveSelectedServices(appWidgetId, selectedServices)
        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(RESULT_OK, resultValue)
        if (demoData.isEnabled()) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                runCatching { quotaRefreshService.refreshNow() }
            }
        }
        lifecycleScope.launch {
            try {
                val glanceId = GlanceAppWidgetManager(this@WidgetConfigurationActivity)
                    .getGlanceIdBy(appWidgetId)
                WidgetRegistry.refresh(this@WidgetConfigurationActivity, glanceId)
            } catch (_: Exception) {
            }
            finish()
        }
    }

    @Composable
    private fun AppTheme(content: @Composable () -> Unit) {
        val themeMode by displayPreferences.themeMode.collectAsStateWithLifecycle()
        val quotaDisplayMode by displayPreferences.quotaDisplayMode.collectAsStateWithLifecycle()
        TokenUsageTheme(
            themeMode = themeMode,
            quotaDisplayMode = quotaDisplayMode,
            content = content
        )
    }
}

@Composable
private fun ServiceCheckRow(
    service: AiService,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
        Spacer(modifier = Modifier.width(8.dp))
        ServiceIcon(
            iconRes = service.iconRes,
            contentDescription = null,
            size = 24.dp,
            tint = if (enabled) Color.Unspecified else Color.Gray
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = service.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!enabled) {
                Text(
                    text = stringResource(R.string.widget_no_credentials),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
