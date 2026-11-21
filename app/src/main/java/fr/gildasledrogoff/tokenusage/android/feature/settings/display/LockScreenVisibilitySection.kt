package fr.gildasledrogoff.tokenusage.feature.settings.display
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.LockScreenVisibility
import fr.gildasledrogoff.tokenusage.ui.components.ChoiceChipRow

@Composable
internal fun LockScreenVisibilitySection(
    lockScreenVisibility: LockScreenVisibility,
    onLockScreenVisibilityChange: (LockScreenVisibility) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.lock_screen_title),
                style = MaterialTheme.typography.titleMedium
            )
            ChoiceChipRow(
                options = LockScreenVisibility.entries,
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
