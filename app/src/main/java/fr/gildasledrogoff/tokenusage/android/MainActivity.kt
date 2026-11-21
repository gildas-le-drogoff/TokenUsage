package fr.gildasledrogoff.tokenusage

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import dagger.hilt.android.AndroidEntryPoint
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.core.security.AppLockManager
import fr.gildasledrogoff.tokenusage.core.security.IntegrityGuard
import fr.gildasledrogoff.tokenusage.core.util.BatteryOptimizationHelper
import fr.gildasledrogoff.tokenusage.core.workmanager.RefreshScheduler
import fr.gildasledrogoff.tokenusage.core.workmanager.WorkManagerInitializer
import fr.gildasledrogoff.tokenusage.feature.credits.CreditsScreen
import fr.gildasledrogoff.tokenusage.feature.dashboard.DashboardScreen
import fr.gildasledrogoff.tokenusage.feature.diagnostics.DiagnosticsScreen
import fr.gildasledrogoff.tokenusage.feature.history.BalanceHistoryScreen
import fr.gildasledrogoff.tokenusage.feature.settings.SettingsAccountsScreen
import fr.gildasledrogoff.tokenusage.feature.settings.SettingsDestination
import fr.gildasledrogoff.tokenusage.feature.settings.SettingsScreen
import fr.gildasledrogoff.tokenusage.feature.settings.alerts.AlertsSettingsScreen
import fr.gildasledrogoff.tokenusage.feature.settings.backup.BackupSettingsScreen
import fr.gildasledrogoff.tokenusage.feature.settings.display.DisplaySettingsScreen
import fr.gildasledrogoff.tokenusage.feature.settings.refresh.RefreshSettingsScreen
import fr.gildasledrogoff.tokenusage.feature.settings.security.SecuritySettingsScreen
import fr.gildasledrogoff.tokenusage.ui.security.AppLockScreen
import fr.gildasledrogoff.tokenusage.ui.security.CompromisedDeviceScreen
import fr.gildasledrogoff.tokenusage.ui.theme.TokenUsageTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject
    lateinit var integrityGuard: IntegrityGuard

    @Inject
    lateinit var displayPreferences: DisplayPreferencesRepository

    @Inject
    lateinit var refreshScheduler: RefreshScheduler

    @Inject
    lateinit var appLock: AppLockManager
    private val batteryOptLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        WorkManagerInitializer.scheduleTokenRefresh(this)
        refreshScheduler.applySaved()
    }

    private fun acceptRiskAndRestart() {
        integrityGuard.acceptRisk()
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                appLock.onAppHidden()
            }
        })
        if (integrityGuard.isVaultLocked) {
            setContent {
                AppTheme {
                    CompromisedDeviceScreen(
                        threats = integrityGuard.threats,
                        onClose = ::finishAndRemoveTask,
                        onAcceptRisk = ::acceptRiskAndRestart
                    )
                }
            }
            return
        }
        setContent {
            AppTheme {
                val locked by appLock.isLocked.collectAsStateWithLifecycle()
                if (locked) {
                    AppLockScreen(
                        onUnlock = appLock::unlock,
                        onDisableLock = { appLock.setEnabled(false) }
                    )
                    return@AppTheme
                }
                if (BuildConfig.IS_DEBUG) {
                    LaunchedEffect(locked) {
                        if (locked) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                    }
                }
                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val permissionState =
                        rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
                    val permissionMessage = stringResource(R.string.notif_permission_required)
                    LaunchedEffect(permissionState.status.isGranted) {
                        if (!permissionState.status.isGranted) {
                            permissionState.launchPermissionRequest()
                        }
                    }
                    LaunchedEffect(permissionState.status) {
                        if (!permissionState.status.isGranted) {
                            snackbarHostState.showSnackbar(permissionMessage)
                        }
                    }
                }
                var showBatteryDialog by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    if (!displayPreferences.batteryPromptAnswered &&
                        !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this@MainActivity)
                    ) {
                        showBatteryDialog = true
                    }
                }
                if (showBatteryDialog) {
                    AlertDialog(
                        onDismissRequest = { showBatteryDialog = false },
                        title = { Text(stringResource(R.string.battery_dialog_title)) },
                        text = { Text(stringResource(R.string.battery_dialog_text)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showBatteryDialog = false
                                displayPreferences.markBatteryPromptAnswered()
                                val launched = runCatching {
                                    batteryOptLauncher.launch(
                                        BatteryOptimizationHelper
                                            .requestIgnoreBatteryOptimizationsIntent(this@MainActivity)
                                    )
                                }.isSuccess
                                if (!launched) {
                                    runCatching {
                                        batteryOptLauncher.launch(
                                            BatteryOptimizationHelper.openBatteryOptimizationSettingsIntent()
                                        )
                                    }
                                }
                            }) {
                                Text(stringResource(R.string.action_allow))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showBatteryDialog = false
                                displayPreferences.markBatteryPromptAnswered()
                            }) {
                                Text(stringResource(R.string.battery_dialog_decline))
                            }
                        }
                    )
                }
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    contentWindowInsets = WindowInsets(0)
                ) { paddingValues ->
                    NavHost(
                        navController = navController,
                        startDestination = "dashboard",
                        modifier = Modifier.padding(paddingValues)
                    ) {
                        composable("dashboard") {
                            DashboardScreen(
                                onNavigateToSettings = {
                                    navController.navigate("settings")
                                },
                                onNavigateToHistory = {
                                    navController.navigate("history")
                                }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onNavigateBack = {
                                    navController.popBackStack()
                                },
                                onNavigateTo = { navController.navigate(it.route) },
                                onNavigateToCredits = {
                                    navController.navigate("credits")
                                },
                                showDiagnostics = SETTINGS_DIAGNOSTICS_ROUTE_READY
                            )
                        }
                        composable(SettingsDestination.ACCOUNTS.route) {
                            SettingsAccountsScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable(SettingsDestination.DISPLAY.route) {
                            DisplaySettingsScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable(SettingsDestination.ALERTS.route) {
                            AlertsSettingsScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable(SettingsDestination.REFRESH.route) {
                            RefreshSettingsScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable(SettingsDestination.BACKUP.route) {
                            BackupSettingsScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable(SettingsDestination.SECURITY.route) {
                            SecuritySettingsScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable("diagnostics") {
                            DiagnosticsScreen(onNavigateBack = { navController.popBackStack() })
                        }
                        composable("credits") {
                            CreditsScreen(
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                        composable("history") {
                            BalanceHistoryScreen(
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
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

private const val SETTINGS_DIAGNOSTICS_ROUTE_READY = true
