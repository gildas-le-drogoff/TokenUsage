package fr.gildasledrogoff.tokenusage.core.alert

import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import kotlinx.serialization.Serializable
import java.time.Instant
import javax.inject.Inject

@Serializable
data class WindowLifecycle(
    val exhausted: Boolean = false,
    val resetsAtMillis: Long? = null,
    val warnedBoundaryMillis: Long? = null,
    val warnedStages: Int = 0
)

@Serializable
data class LifecycleState(val windows: Map<String, WindowLifecycle> = emptyMap())

data class ResetEvent(val quota: QuotaInfo, val windowLabel: String)

class QuotaLifecycleEvaluator @Inject constructor() {
    data class Evaluation(
        val alerts: List<QuotaAlert>,
        val weeklyResets: List<ResetEvent>,
        val state: LifecycleState
    )

    fun evaluate(
        quotas: List<QuotaInfo>,
        config: AlertConfig,
        state: LifecycleState,
        now: Instant
    ): Evaluation {
        val nowMillis = now.toEpochMilli()
        val next = HashMap<String, WindowLifecycle>()
        val alerts = mutableListOf<QuotaAlert>()
        val resets = mutableListOf<ResetEvent>()
        val handledAccounts = HashSet<String>()
        for (quota in quotas.distinctBy { it.accountKey }) {
            handledAccounts += quota.accountKey
            val enabled = config.ruleFor(quota).enabled
            val prefix = quota.accountKey + SEP
            for (window in quota.windows) {
                val key = prefix + window.label
                val previous = state.windows[key]
                val resetsAt = window.resetsAt?.toEpochMilli()
                val exhausted = window.utilization >= EXHAUSTED_UTILIZATION
                val reset = previous?.resetsAtMillis != null && resetsAt != null &&
                        previous.resetsAtMillis <= nowMillis && resetsAt > nowMillis &&
                        resetsAt > previous.resetsAtMillis
                var entry = WindowLifecycle(exhausted = exhausted, resetsAtMillis = resetsAt)
                if (previous != null && previous.warnedBoundaryMillis == resetsAt) {
                    entry = entry.copy(
                        warnedBoundaryMillis = previous.warnedBoundaryMillis,
                        warnedStages = previous.warnedStages
                    )
                }
                if (enabled) {
                    if (exhausted && previous?.exhausted != true) {
                        alerts += alert(quota, window.label, AlertType.WINDOW_EXHAUSTED, window.utilization, window.resetsAt)
                    } else if (!exhausted && previous?.exhausted == true && !reset) {
                        alerts += alert(quota, window.label, AlertType.WINDOW_RESTORED, window.utilization, window.resetsAt)
                    }
                    if (reset && window.label == WEEKLY_LABEL && config.celebrateResets) {
                        resets += ResetEvent(quota, window.label)
                    }
                    if (window.label == WEEKLY_LABEL && window.resetsAt != null && config.warnBeforeReset) {
                        entry = upcoming(quota, window.label, window.utilization, window.resetsAt, now, entry, alerts)
                    }
                }
                next[key] = entry
            }
        }
        for ((key, value) in state.windows) {
            if (handledAccounts.none { key.startsWith(it + SEP) }) next.putIfAbsent(key, value)
        }
        return Evaluation(alerts, resets, LifecycleState(next))
    }

    private fun upcoming(
        quota: QuotaInfo,
        label: String,
        utilization: Double,
        resetsAt: Instant,
        now: Instant,
        entry: WindowLifecycle,
        alerts: MutableList<QuotaAlert>
    ): WindowLifecycle {
        if (!resetsAt.isAfter(now) || 1.0 - utilization < MIN_UNUSED_FRACTION) return entry
        val untilReset = resetsAt.toEpochMilli() - now.toEpochMilli()
        val stage = UPCOMING_LEAD_HOURS.indices.lastOrNull { untilReset <= UPCOMING_LEAD_HOURS[it] * HOUR_MILLIS }
            ?: return entry
        if (entry.warnedStages and (1 shl stage) != 0) return entry
        alerts += alert(quota, label, AlertType.RESET_UPCOMING, utilization, resetsAt)
        return entry.copy(
            warnedBoundaryMillis = resetsAt.toEpochMilli(),
            warnedStages = (1 shl (stage + 1)) - 1
        )
    }

    private fun alert(
        quota: QuotaInfo,
        label: String,
        type: AlertType,
        utilization: Double,
        resetsAt: Instant?
    ) = QuotaAlert(
        accountKey = quota.accountKey,
        service = quota.service,
        accountLabel = quota.accountLabel,
        type = type,
        windowLabel = label,
        currentValue = utilization,
        resetsAt = resetsAt
    )

    companion object {
        const val WEEKLY_LABEL = "7-Day"
        const val EXHAUSTED_UTILIZATION = 0.995
        const val MIN_UNUSED_FRACTION = 0.10
        val UPCOMING_LEAD_HOURS = listOf(24L, 1L)
        private const val HOUR_MILLIS = 3_600_000L
        private const val SEP = "|"
    }
}
