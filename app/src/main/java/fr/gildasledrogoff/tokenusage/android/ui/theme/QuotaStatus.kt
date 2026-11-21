package fr.gildasledrogoff.tokenusage.ui.theme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageSeverity
enum class QuotaStatus { HEALTHY, LOW, CRITICAL }
const val LOW_REMAINING_THRESHOLD = 0.40
const val CRITICAL_REMAINING_THRESHOLD = 0.15
fun quotaStatusOf(remaining: Double, severity: UsageSeverity = UsageSeverity.NORMAL): QuotaStatus {
    val fromRemaining = when {
        remaining <= CRITICAL_REMAINING_THRESHOLD -> QuotaStatus.CRITICAL
        remaining <= LOW_REMAINING_THRESHOLD -> QuotaStatus.LOW
        else -> QuotaStatus.HEALTHY
    }
    val fromSeverity = when (severity) {
        UsageSeverity.CRITICAL -> QuotaStatus.CRITICAL
        UsageSeverity.WARNING -> QuotaStatus.LOW
        UsageSeverity.NORMAL -> QuotaStatus.HEALTHY
    }
    return maxOf(fromRemaining, fromSeverity)
}
@Immutable
data class StatusColorPair(val main: Color, val container: Color, val onContainer: Color)
@Immutable
data class StatusColors(
    val healthy: StatusColorPair,
    val low: StatusColorPair,
    val critical: StatusColorPair
) {
    operator fun get(status: QuotaStatus): StatusColorPair = when (status) {
        QuotaStatus.HEALTHY -> healthy
        QuotaStatus.LOW -> low
        QuotaStatus.CRITICAL -> critical
    }
}
internal val LightStatusColors = StatusColors(
    healthy = StatusColorPair(Color(0xFF1B7F3B), Color(0xFFCDEFD6), Color(0xFF00391A)),
    low = StatusColorPair(Color(0xFF9A5B00), Color(0xFFFFE2B8), Color(0xFF3D2300)),
    critical = StatusColorPair(Color(0xFFBA1A1A), Color(0xFFFFDAD6), Color(0xFF410002))
)
internal val DarkStatusColors = StatusColors(
    healthy = StatusColorPair(Color(0xFF7BD893), Color(0xFF0E4A24), Color(0xFFCDEFD6)),
    low = StatusColorPair(Color(0xFFFFB955), Color(0xFF5A3600), Color(0xFFFFE2B8)),
    critical = StatusColorPair(Color(0xFFFFB4AB), Color(0xFF93000A), Color(0xFFFFDAD6))
)
val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }
val QuotaStatus.colors: StatusColorPair
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current[this]
