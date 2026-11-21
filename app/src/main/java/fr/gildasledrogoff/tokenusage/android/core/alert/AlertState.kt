package fr.gildasledrogoff.tokenusage.core.alert
import kotlinx.serialization.Serializable

@Serializable
data class TimedSample(
    val value: Double,
    val atMillis: Long,
    val resetsAtMillis: Long? = null
)

@Serializable
data class AlertState(
    val fired: Map<String, Long> = emptyMap(),
    val samples: Map<String, List<TimedSample>> = emptyMap()
)
