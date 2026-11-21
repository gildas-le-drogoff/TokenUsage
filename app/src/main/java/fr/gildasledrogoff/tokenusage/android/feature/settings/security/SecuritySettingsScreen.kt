package fr.gildasledrogoff.tokenusage.feature.settings.security
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.feature.settings.common.SettingsSubScreen
import fr.gildasledrogoff.tokenusage.feature.settings.common.ToggleSection
import fr.gildasledrogoff.tokenusage.ui.security.BiometricGate

@Composable
fun SecuritySettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SecuritySettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
                onError = { }
            )
        }
    }
    SettingsSubScreen(title = stringResource(R.string.security_title), onNavigateBack = onNavigateBack) {
        AppLockSection(
            appLockEnabled = uiState.appLockEnabled,
            onToggle = { want ->
                if (!want) {
                    viewModel.setAppLockEnabled(false)
                } else {
                    confirmAppLockWithBiometric { viewModel.setAppLockEnabled(true) }
                }
            }
        )
        
        if (uiState.demoDataAvailable) {
            ToggleSection(
                title = stringResource(R.string.demo_data_title),
                subtitle = stringResource(R.string.demo_data_subtitle),
                checked = uiState.demoDataEnabled,
                onToggle = { viewModel.setDemoDataEnabled(it) }
            )
        }
        DangerZoneSection(onDeleteAll = { viewModel.showDeleteConfirmDialog() })
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
}
@Composable
private fun AppLockSection(appLockEnabled: Boolean, onToggle: (Boolean) -> Unit) {
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
                Switch(checked = appLockEnabled, onCheckedChange = onToggle)
            }
        }
    }
}
