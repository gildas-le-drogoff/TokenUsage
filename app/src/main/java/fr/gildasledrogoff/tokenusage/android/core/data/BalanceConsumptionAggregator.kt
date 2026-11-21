package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionPoint
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

object BalanceConsumptionAggregator {
    data class Sample(
        val totalBalance: Double,
        val currency: String,
        val timestamp: Instant
    )

    fun daily(
        samples: List<Sample>,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<ConsumptionPoint> {
        return aggregate(samples, zone) { instant -> instant.atZone(zone).toLocalDate().toString() }
    }

    fun monthly(
        samples: List<Sample>,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<ConsumptionPoint> {
        return aggregate(samples, zone) { instant ->
            YearMonth.from(instant.atZone(zone)).toString()
        }
    }

    private fun aggregate(
        samples: List<Sample>,
        zone: ZoneId,
        bucketOf: (Instant) -> String
    ): List<ConsumptionPoint> {
        val ordered = samples.sortedBy { it.timestamp }
        if (ordered.size < 2) return emptyList()
        val consumedByBucket = LinkedHashMap<String, Double>()
        for (i in 1 until ordered.size) {
            val previous = ordered[i - 1]
            val current = ordered[i]
            val consumed = if (previous.currency != current.currency) 0.0
            else (previous.totalBalance - current.totalBalance).coerceAtLeast(0.0)
            val bucket = bucketOf(current.timestamp)
            consumedByBucket[bucket] = (consumedByBucket[bucket] ?: 0.0) + consumed
        }
        val currency = ordered.last().currency
        return consumedByBucket.map { (bucket, consumed) ->
            ConsumptionPoint(periodKey = bucket, consumed = consumed, currency = currency)
        }
    }
}
