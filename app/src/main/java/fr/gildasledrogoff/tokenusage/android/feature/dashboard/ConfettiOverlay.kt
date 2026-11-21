package fr.gildasledrogoff.tokenusage.feature.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.sin
import kotlin.random.Random

private data class Piece(
    val x: Float,
    val delay: Float,
    val speed: Float,
    val sway: Float,
    val phase: Float,
    val spin: Float,
    val size: Float,
    val color: Color
)

@Composable
fun ConfettiOverlay(colors: List<Color>, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val pieces = remember(colors) {
        val random = Random(System.nanoTime())
        List(PIECE_COUNT) {
            Piece(
                x = random.nextFloat(),
                delay = random.nextFloat() * 0.35f,
                speed = 0.7f + random.nextFloat() * 0.6f,
                sway = 0.01f + random.nextFloat() * 0.03f,
                phase = random.nextFloat() * 6.28f,
                spin = 180f + random.nextFloat() * 540f,
                size = 6f + random.nextFloat() * 8f,
                color = colors[random.nextInt(colors.size)]
            )
        }
    }
    val progress = remember { Animatable(0f) }
    val finished = rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(DURATION_MILLIS, easing = LinearEasing))
        finished.value()
    }
    Canvas(modifier.fillMaxSize()) {
        val t = progress.value
        pieces.forEach { piece ->
            val local = ((t - piece.delay) / (1f - piece.delay)).coerceIn(0f, 1f)
            if (local <= 0f || local >= 1f) return@forEach
            val y = (local * piece.speed * 1.2f) * size.height - piece.size
            val x = (piece.x + sin(local * 12f + piece.phase) * piece.sway) * size.width
            rotate(piece.spin * local, pivot = Offset(x, y)) {
                drawRect(
                    color = piece.color.copy(alpha = 1f - local * local),
                    topLeft = Offset(x, y),
                    size = Size(piece.size, piece.size * 0.6f)
                )
            }
        }
    }
}

private const val PIECE_COUNT = 90
private const val DURATION_MILLIS = 4500
