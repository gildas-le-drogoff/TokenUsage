package fr.gildasledrogoff.tokenusage.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.ui.theme.WidthClass
import fr.gildasledrogoff.tokenusage.ui.theme.dimens


private val FieldGap = 6.dp

private val FieldGapColumns = 12.dp


@Composable
internal fun SettingsFields(vararg fields: @Composable (Modifier) -> Unit) {
    val paired = MaterialTheme.dimens.widthClass != WidthClass.COMPACT
    Column(verticalArrangement = Arrangement.spacedBy(FieldGap)) {
        if (paired) {
            fields.toList().chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(FieldGapColumns)) {
                    pair.forEach { field -> field(Modifier.weight(1f)) }
                    if (pair.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            fields.forEach { field -> field(Modifier.fillMaxWidth()) }
        }
    }
}
