package fr.gildasledrogoff.tokenusage.ui.theme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }
fun widthClassOf(widthDp: Int): WidthClass = when {
    widthDp < 600 -> WidthClass.COMPACT
    widthDp < 840 -> WidthClass.MEDIUM
    else -> WidthClass.EXPANDED
}
@Immutable
data class Dimens(
    val widthClass: WidthClass,
    val spaceXs: Dp,
    val spaceS: Dp,
    val spaceM: Dp,
    val spaceL: Dp,
    val screenPadding: Dp,
    val cardPadding: Dp,
    val cardCorner: Dp,
    val gaugeThickness: Dp,
    val serviceIcon: Dp,
    val inlineIcon: Dp,
    val cardMinWidth: Dp,
    val inlineChartHeight: Dp
) {
    companion object {
        fun forWidth(widthClass: WidthClass): Dimens = when (widthClass) {
            WidthClass.COMPACT -> Dimens(
                widthClass, 4.dp, 8.dp, 12.dp, 16.dp,
                screenPadding = 16.dp, cardPadding = 16.dp, cardCorner = 20.dp,
                gaugeThickness = 10.dp, serviceIcon = 32.dp, inlineIcon = 14.dp,
                cardMinWidth = 320.dp, inlineChartHeight = 140.dp
            )
            WidthClass.MEDIUM -> Dimens(
                widthClass, 4.dp, 8.dp, 16.dp, 24.dp,
                screenPadding = 24.dp, cardPadding = 20.dp, cardCorner = 24.dp,
                gaugeThickness = 12.dp, serviceIcon = 36.dp, inlineIcon = 16.dp,
                cardMinWidth = 340.dp, inlineChartHeight = 160.dp
            )
            WidthClass.EXPANDED -> Dimens(
                widthClass, 6.dp, 12.dp, 16.dp, 32.dp,
                screenPadding = 32.dp, cardPadding = 24.dp, cardCorner = 24.dp,
                gaugeThickness = 12.dp, serviceIcon = 40.dp, inlineIcon = 16.dp,
                cardMinWidth = 380.dp, inlineChartHeight = 180.dp
            )
        }
    }
}
val LocalDimens = staticCompositionLocalOf { Dimens.forWidth(WidthClass.COMPACT) }
