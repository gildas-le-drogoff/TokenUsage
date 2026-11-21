package fr.gildasledrogoff.tokenusage.feature.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.feature.settings.common.SettingsSubScreen

/** Sub-screens reachable from the settings menu. [route] is the navigation route. */
enum class SettingsDestination(
    val route: String,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    @DrawableRes val icon: Int
) {
    ACCOUNTS("settings/accounts", R.string.settings_menu_accounts, R.string.settings_menu_accounts_summary, R.drawable.ic_login_rounded),
    DISPLAY("settings/display", R.string.display_title, R.string.settings_menu_display_summary, R.drawable.ic_contrast_rounded),
    ALERTS("settings/alerts", R.string.settings_menu_alerts, R.string.settings_menu_alerts_summary, R.drawable.ic_warning_rounded),
    REFRESH("settings/refresh", R.string.settings_menu_refresh, R.string.settings_menu_refresh_summary, R.drawable.ic_refresh),
    BACKUP("settings/backup", R.string.backup_title, R.string.settings_menu_backup_summary, R.drawable.ic_ios_share_rounded),
    SECURITY("settings/security", R.string.security_title, R.string.settings_menu_security_summary, R.drawable.ic_lock_rounded),
    DIAGNOSTICS("diagnostics", R.string.settings_menu_diagnostics, R.string.settings_menu_diagnostics_summary, R.drawable.ic_check_circle_rounded)
}

/**
 * Settings menu: one entry per domain, each opening its own sub-screen.
 * The diagnostics entry only shows when [onOpenDiagnostics] is given.
 */
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateTo: (SettingsDestination) -> Unit,
    onNavigateToCredits: () -> Unit,
    showDiagnostics: Boolean = false
) {
    SettingsSubScreen(title = stringResource(R.string.settings), onNavigateBack = onNavigateBack) {
        SettingsDestination.entries
            .filter { it != SettingsDestination.DIAGNOSTICS || showDiagnostics }
            .forEach { destination ->
                SettingsMenuEntry(
                    title = stringResource(destination.title),
                    summary = stringResource(destination.summary),
                    onClick = { onNavigateTo(destination) }
                ) {
                    Icon(
                        painter = painterResource(destination.icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

        SettingsMenuEntry(
            title = stringResource(R.string.settings_credits_title),
            summary = stringResource(R.string.settings_credits_subtitle),
            onClick = onNavigateToCredits
        ) {
            Image(
                painter = painterResource(R.drawable.ic_lama),
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

@Composable
private fun SettingsMenuEntry(
    title: String,
    summary: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit
) {
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
            leading()
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
