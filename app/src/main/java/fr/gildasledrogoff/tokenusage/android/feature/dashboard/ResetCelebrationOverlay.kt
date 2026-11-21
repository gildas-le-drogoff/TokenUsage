package fr.gildasledrogoff.tokenusage.feature.dashboard

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.alert.ResetCelebration
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
fun ResetCelebrationOverlay(event: ResetCelebration, onFinished: () -> Unit) {
    val colors = remember(event.service) {
        listOf(Color(event.service.brandColor), Color(0xFFFFC107), Color(0xFF4CAF50), Color(0xFFE91E63))
    }
    val animationsEnabled = remember(event) { ValueAnimator.areAnimatorsEnabled() }
    if (animationsEnabled) {
        ConfettiOverlay(colors = colors, onFinished = onFinished)
    } else {
        StillCelebration(event, colors, onFinished)
    }
}

@Composable
private fun StillCelebration(event: ResetCelebration, colors: List<Color>, onFinished: () -> Unit) {
    val finished = rememberUpdatedState(onFinished)
    LaunchedEffect(event) {
        delay(STILL_DURATION_MILLIS)
        finished.value()
    }
    val pieces = remember(event) {
        val random = Random(event.atMillis)
        List(STILL_PIECE_COUNT) {
            StillPiece(
                x = random.nextFloat(),
                y = random.nextFloat() * 0.45f,
                angle = random.nextFloat() * 360f,
                size = 6f + random.nextFloat() * 8f,
                color = colors[random.nextInt(colors.size)]
            )
        }
    }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            pieces.forEach { piece ->
                val topLeft = Offset(piece.x * size.width, piece.y * size.height)
                rotate(piece.angle, pivot = topLeft) {
                    drawRect(piece.color, topLeft, Size(piece.size, piece.size * 0.6f))
                }
            }
        }
        Card(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(24.dp)
                .fillMaxWidth()
                .clickable { finished.value() }
                .semantics { liveRegion = LiveRegionMode.Polite },
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "🎉", style = MaterialTheme.typography.displaySmall)
                Text(
                    text = stringResource(R.string.celebration_reset_title),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.celebration_reset_body, event.service.displayName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private data class StillPiece(
    val x: Float,
    val y: Float,
    val angle: Float,
    val size: Float,
    val color: Color
)

private const val STILL_PIECE_COUNT = 40
private const val STILL_DURATION_MILLIS = 6_000L
