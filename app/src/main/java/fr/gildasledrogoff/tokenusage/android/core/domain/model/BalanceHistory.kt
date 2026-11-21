package fr.gildasledrogoff.tokenusage.core.domain.model
import java.time.Instant
data class BalanceSample(
    val accountId: String,
    val accountLabel: String,
    val currency: String,
    val totalBalance: Double,
    val timestamp: Instant
)
data class ConsumptionPoint(
    val periodKey: String,
    val consumed: Double,
    val currency: String
)
enum class ConsumptionGranularity(val sampleWindowDays: Int) {
    DAILY(sampleWindowDays = 30),
    MONTHLY(sampleWindowDays = 370)
}
