package fr.gildasledrogoff.tokenusage.core.alert

import kotlinx.serialization.Serializable

/** One measurement of a quota window (fraction 0..1) or of a balance. */
@Serializable
data class TimedSample(
    val value: Double,
    val atMillis: Long,
    val resetsAtMillis: Long? = null
)

/**
 * What the evaluator remembers between two refreshes.
 *
 * - [fired]: alerts currently raised (key -> reset instant of the window at that time, 0 if none).
 *   An alert fires again only once its condition went false, or when the window was reset.
 * - [samples]: recent measurements, used to extrapolate the usage speed.
 */
@Serializable
data class AlertState(
    val fired: Map<String, Long> = emptyMap(),
    val samples: Map<String, List<TimedSample>> = emptyMap()
)
