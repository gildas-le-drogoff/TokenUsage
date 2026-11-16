package fr.gildasledrogoff.tokenusage.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.security.IntegrityThreat


@Composable
fun CompromisedDeviceScreen(
    threats: Set<IntegrityThreat>,
    onClose: () -> Unit,
    onAcceptRisk: (() -> Unit)? = null
) {
    val isExpanded = LocalConfiguration.current.screenWidthDp >= EXPANDED_WIDTH_DP
    val spacing = if (isExpanded) 24.dp else 16.dp
    val contentFraction = if (isExpanded) 0.6f else 1f

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(spacing),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(contentFraction),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_gpp_bad),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = stringResource(R.string.integrity_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.integrity_message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (threats.isNotEmpty()) {
                    val labels = threats.map { "• " + threatLabel(it) }
                    Text(
                        text = (listOf(stringResource(R.string.integrity_threats_header)) + labels)
                            .joinToString("\n"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                if (onAcceptRisk == null) {
                    Text(
                        text = stringResource(R.string.integrity_open_app),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.integrity_close))
                }
                if (onAcceptRisk != null) {
                    OutlinedButton(onClick = onAcceptRisk, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.integrity_accept_risk))
                    }
                    Text(
                        text = stringResource(R.string.integrity_accept_risk_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun threatLabel(threat: IntegrityThreat): String = stringResource(
    when (threat) {
        IntegrityThreat.ROOT -> R.string.integrity_threat_root
        IntegrityThreat.HOOKING_FRAMEWORK -> R.string.integrity_threat_hooking
        IntegrityThreat.DEBUGGER -> R.string.integrity_threat_debugger
        IntegrityThreat.TAMPERED_BUILD -> R.string.integrity_threat_tampered
    }
)

private const val EXPANDED_WIDTH_DP = 600
