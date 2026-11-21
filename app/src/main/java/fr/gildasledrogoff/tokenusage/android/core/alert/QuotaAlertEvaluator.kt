package fr.gildasledrogoff.tokenusage.core.alert

import fr.gildasledrogoff.tokenusage.core.domain.model.AccountBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

class QuotaAlertEvaluator @Inject constructor(
    private val predictor: DepletionPredictor,
    private val balanceSeries: BalanceSeriesSource,
    private val clock: Clock
) {
    data class Evaluation(val alerts: List<QuotaAlert>, val state: AlertState)

    suspend fun evaluate(
        quotas: List<QuotaInfo>,
        config: AlertConfig,
        state: AlertState
    ): Evaluation {
        val now = clock.instant()
        val lookback = Duration.ofDays(config.forecastWindowDays.toLong())
        val fired = state.fired.toMutableMap()
        val samples = pruned(state.samples, now, lookback)
        val alerts = mutableListOf<QuotaAlert>()
        for (quota in quotas.distinctBy { it.accountKey }) {
            val rule = config.ruleFor(quota)
            val prefix = quota.accountKey + SEP
            val touched = HashMap<String, Long>()
            if (rule.enabled) {
                evaluateWindows(quota, rule, prefix, now, lookback, fired, samples, touched, alerts)
                evaluateBalances(
                    quota,
                    rule,
                    prefix,
                    now,
                    lookback,
                    fired,
                    samples,
                    touched,
                    alerts
                )
            }
            fired.keys.removeAll { it.startsWith(prefix) && it !in touched }
            fired.putAll(touched)
            if (!rule.enabled) samples.keys.removeAll { it.startsWith(prefix) }
        }
        return Evaluation(alerts, AlertState(fired, samples))
    }

    private fun evaluateWindows(
        quota: QuotaInfo,
        rule: AlertRule,
        prefix: String,
        now: Instant,
        lookback: Duration,
        fired: MutableMap<String, Long>,
        samples: MutableMap<String, List<TimedSample>>,
        touched: MutableMap<String, Long>,
        alerts: MutableList<QuotaAlert>
    ) {
        val windows =
            quota.windows.filter { rule.quotaWindowLabel == null || it.label == rule.quotaWindowLabel }
        val newlyOverThreshold = mutableListOf<Pair<UsageWindow, DepletionForecast?>>()
        val newlyDepleting = mutableListOf<Pair<UsageWindow, DepletionForecast>>()
        val horizon = rule.depletionHorizonHours?.let { Duration.ofHours(it.toLong()) }
        for (window in windows) {
            val at = minOf(quota.fetchedAt, now)
            val key = "${prefix}W$SEP${window.label}"
            val series = recordWindowSample(samples, key, window, at)
            val forecast = predictor
                .predictQuota(series.map { it.toTimedValue() }, lookback)
                ?.takeIf { it.isBefore(window.resetsAt) }
            val thresholdKey = "${prefix}Q$SEP${window.label}"
            val overThreshold =
                rule.quotaThreshold != null && window.utilization >= rule.quotaThreshold
            if (overThreshold) {
                touched.mark(thresholdKey, window.resetsAt)
                if (shouldFire(
                        fired,
                        thresholdKey,
                        window.resetsAt,
                        now
                    )
                ) newlyOverThreshold += window to forecast
            }
            val depletionKey = "${prefix}D$SEP${window.label}"
            if (horizon != null && forecast != null && forecast.isWithin(horizon, now)) {
                touched.mark(depletionKey, window.resetsAt)
                if (shouldFire(
                        fired,
                        depletionKey,
                        window.resetsAt,
                        now
                    )
                ) newlyDepleting += window to forecast
            }
        }
        newlyOverThreshold.maxByOrNull { it.first.utilization }?.let { (window, forecast) ->
            alerts += QuotaAlert(
                accountKey = quota.accountKey,
                service = quota.service,
                accountLabel = quota.accountLabel,
                type = AlertType.QUOTA_THRESHOLD,
                windowLabel = window.label,
                currentValue = window.utilization,
                threshold = rule.quotaThreshold,
                depletionAt = forecast?.depletionAt
            )
        }
        newlyDepleting.minByOrNull { it.second.depletionAt }?.let { (window, forecast) ->
            alerts += QuotaAlert(
                accountKey = quota.accountKey,
                service = quota.service,
                accountLabel = quota.accountLabel,
                type = AlertType.DEPLETION_FORECAST,
                windowLabel = window.label,
                currentValue = window.utilization,
                depletionAt = forecast.depletionAt
            )
        }
    }

    private suspend fun evaluateBalances(
        quota: QuotaInfo,
        rule: AlertRule,
        prefix: String,
        now: Instant,
        lookback: Duration,
        fired: MutableMap<String, Long>,
        samples: MutableMap<String, List<TimedSample>>,
        touched: MutableMap<String, Long>,
        alerts: MutableList<QuotaAlert>
    ) {
        val entries = quota.accountBalances.mapIndexedNotNull { index, entry ->
            entry.balance?.let { index to entry }
        }
        val horizon = rule.depletionHorizonHours?.let { Duration.ofHours(it.toLong()) }
        val newlyLow = mutableListOf<Triple<AccountBalance, Double, DepletionForecast?>>()
        val newlyDepleting = mutableListOf<Triple<AccountBalance, Double, DepletionForecast>>()
        for ((index, entry) in entries) {
            val balance = entry.balance ?: continue
            val at = minOf(quota.fetchedAt, now)
            val ownKey = "${prefix}S$SEP$index"
            val own =
                recordBalanceSample(samples, ownKey, balance.totalBalance, at, now.minus(lookback))
            val stored = quota.accountId
                ?.takeIf { entries.size == 1 }
                ?.let { balanceSeries.series(quota.service, it, now.minus(lookback)) }
                .orEmpty()
            val base = if (stored.size >= 2) stored else own.map { it.toTimedValue() }
            val last = base.maxByOrNull { it.at }
            val points =
                if (last == null || at.isAfter(last.at.plusSeconds(CURRENT_POINT_GAP_SECONDS))) {
                    base + TimedValue(balance.totalBalance, at)
                } else {
                    base
                }
            val forecast = predictor.predictBalance(points, target = 0.0, lookback = lookback)
            val lowKey = "${prefix}B$SEP$index"
            val low = rule.balanceThreshold != null && balance.totalBalance <= rule.balanceThreshold
            if (low) {
                touched.mark(lowKey, null)
                if (shouldFire(fired, lowKey, null, now)) newlyLow += Triple(
                    entry,
                    balance.totalBalance,
                    forecast
                )
            }
            val depletionKey = "${prefix}E$SEP$index"
            if (horizon != null && forecast != null && forecast.isWithin(horizon, now)) {
                touched.mark(depletionKey, null)
                if (shouldFire(fired, depletionKey, null, now)) {
                    newlyDepleting += Triple(entry, balance.totalBalance, forecast)
                }
            }
        }
        newlyLow.minByOrNull { it.second }?.let { (entry, value, forecast) ->
            alerts += QuotaAlert(
                accountKey = quota.accountKey,
                service = quota.service,
                accountLabel = labelOf(quota, entry),
                type = AlertType.LOW_BALANCE,
                currentValue = value,
                threshold = rule.balanceThreshold,
                currency = entry.balance?.currency,
                depletionAt = forecast?.depletionAt
            )
        }
        newlyDepleting.minByOrNull { it.third.depletionAt }?.let { (entry, value, forecast) ->
            alerts += QuotaAlert(
                accountKey = quota.accountKey,
                service = quota.service,
                accountLabel = labelOf(quota, entry),
                type = AlertType.DEPLETION_FORECAST,
                currentValue = value,
                currency = entry.balance?.currency,
                depletionAt = forecast.depletionAt
            )
        }
    }

    private fun shouldFire(
        fired: Map<String, Long>,
        key: String,
        resetsAt: Instant?,
        now: Instant
    ): Boolean {
        val previous = fired[key] ?: return true
        val current = resetsAt?.toEpochMilli() ?: return false
        return previous != NO_RESET && previous <= now.toEpochMilli() && current > previous
    }

    private fun MutableMap<String, Long>.mark(key: String, resetsAt: Instant?) {
        put(key, resetsAt?.toEpochMilli() ?: NO_RESET)
    }

    private fun labelOf(quota: QuotaInfo, entry: AccountBalance): String? =
        quota.accountLabel ?: entry.label.takeIf { it.isNotBlank() }

    private fun recordWindowSample(
        samples: MutableMap<String, List<TimedSample>>,
        key: String,
        window: UsageWindow,
        at: Instant
    ): List<TimedSample> {
        var series = samples[key].orEmpty()
        val last = series.lastOrNull()
        val resetsAt = window.resetsAt?.toEpochMilli()
        val newCycle = last != null && (
                window.utilization < last.value - CYCLE_DROP ||
                        (resetsAt != null && last.resetsAtMillis != null &&
                                last.resetsAtMillis <= at.toEpochMilli() && resetsAt > last.resetsAtMillis)
                )
        if (newCycle) series = emptyList()
        series = append(series, TimedSample(window.utilization, at.toEpochMilli(), resetsAt))
        samples[key] = series
        return series
    }

    private fun recordBalanceSample(
        samples: MutableMap<String, List<TimedSample>>,
        key: String,
        value: Double,
        at: Instant,
        earliest: Instant
    ): List<TimedSample> {
        val series = append(samples[key].orEmpty(), TimedSample(value, at.toEpochMilli()))
            .filter { it.atMillis >= earliest.toEpochMilli() }
        samples[key] = series
        return series
    }

    private fun append(series: List<TimedSample>, sample: TimedSample): List<TimedSample> {
        val last = series.lastOrNull()
        var result = when {
            last == null -> listOf(sample)
            sample.atMillis <= last.atMillis -> series
            sample.atMillis - last.atMillis < MIN_SAMPLE_GAP_MILLIS -> series.dropLast(1) + sample
            else -> series + sample
        }
        if (result.size > MAX_SAMPLES) {
            result =
                result.filterIndexed { index, _ -> index % 2 == 0 || index == result.lastIndex }
        }
        return result
    }

    private fun pruned(
        samples: Map<String, List<TimedSample>>,
        now: Instant,
        lookback: Duration
    ): MutableMap<String, List<TimedSample>> {
        val earliest = now.minus(lookback).toEpochMilli()
        val result = HashMap<String, List<TimedSample>>()
        for ((key, series) in samples) {
            if (series.isNotEmpty() && series.last().atMillis >= earliest) result[key] = series
        }
        return result
    }

    private fun TimedSample.toTimedValue() = TimedValue(value, Instant.ofEpochMilli(atMillis))

    private companion object {
        const val SEP = "|"
        const val NO_RESET = 0L
        const val CYCLE_DROP = 1e-3
        const val MIN_SAMPLE_GAP_MILLIS = 5 * 60 * 1000L
        const val MAX_SAMPLES = 96
        const val CURRENT_POINT_GAP_SECONDS = 60L
    }
}
