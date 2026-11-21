package fr.gildasledrogoff.tokenusage.ui.security
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import androidx.fragment.app.FragmentActivity
@Composable
fun AppLockScreen(
    onUnlock: () -> Unit,
    onDisableLock: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var errorText by remember { mutableStateOf<String?>(null) }
    var noAuthenticator by remember { mutableStateOf(false) }
    val unavailableText = stringResource(R.string.app_lock_unavailable)
    val unlockTitle = stringResource(R.string.unlock_title)
    val unlockSubtitle = stringResource(R.string.unlock_subtitle)
    val promptUnlock: () -> Unit = {
        errorText = null
        if (activity == null) {
            errorText = unavailableText
        } else {
            when (BiometricGate.availability(activity)) {
                BiometricGate.Availability.READY -> BiometricGate.authenticate(
                    activity = activity,
                    title = unlockTitle,
                    subtitle = unlockSubtitle,
                    onSuccess = onUnlock,
                    onError = { errorText = it.toString() }
                )
                BiometricGate.Availability.NO_HARDWARE,
                BiometricGate.Availability.NONE_ENROLLED -> noAuthenticator = true
                BiometricGate.Availability.UNAVAILABLE ->
                    errorText = unavailableText
            }
        }
    }
    LaunchedEffect(Unit) { promptUnlock() }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lock_rounded),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.unlock_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.unlock_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            if (errorText != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            if (noAuthenticator) {
                Text(
                    text = stringResource(R.string.app_lock_no_authenticator),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onDisableLock) {
                    Text(stringResource(R.string.app_lock_disable_button))
                }
            } else {
                Button(onClick = promptUnlock) {
                    Icon(
                        painter = painterResource(R.drawable.ic_fingerprint_rounded),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.unlock_action))
                }
            }
        }
    }
}
