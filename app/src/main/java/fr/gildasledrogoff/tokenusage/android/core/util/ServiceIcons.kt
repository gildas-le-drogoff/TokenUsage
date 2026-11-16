package fr.gildasledrogoff.tokenusage.core.util

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService


val AiService.iconRes: Int
    @DrawableRes get() = when (this) {
        AiService.CLAUDE -> R.drawable.ic_service_claude
        AiService.CODEX -> R.drawable.ic_service_codex
        AiService.GEMINI -> R.drawable.ic_service_gemini
        AiService.DEEPSEEK -> R.drawable.ic_service_deepseek
        AiService.ZAI -> R.drawable.ic_service_zai
        AiService.COPILOT -> R.drawable.ic_service_copilot
        AiService.OPENROUTER -> R.drawable.ic_service_openrouter
        AiService.OPENAI -> R.drawable.ic_service_openai
    }


@Composable
fun ServiceIcon(
    @DrawableRes iconRes: Int,
    contentDescription: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified
) {
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    if (isDarkTheme) {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.2f))
                .background(MaterialTheme.colorScheme.inverseSurface)
                .padding(size * 0.15f)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                modifier = Modifier.size(size * 0.7f),
                tint = tint
            )
        }
    } else {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = modifier.size(size),
            tint = tint
        )
    }
}
