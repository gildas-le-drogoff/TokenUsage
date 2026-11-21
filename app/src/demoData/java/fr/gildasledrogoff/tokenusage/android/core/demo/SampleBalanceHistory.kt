package fr.gildasledrogoff.tokenusage.core.demo
import fr.gildasledrogoff.tokenusage.core.data.BalanceConsumptionAggregator
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.BalanceSample
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionPoint
import fr.gildasledrogoff.tokenusage.core.domain.model.DeepseekAccount
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.random.Random
data class SampleAccountSpec(
    val account: DeepseekAccount,
    val currency: String,
    val startBalance: Double,
    val dailySpend: Double,
    val topUpThreshold: Double,
    val topUpAmount: Double,
    val historyStartDaysAgo: Int,
    val historyEndDaysAgo: Int = 0,
    val seed: Long
)
class SampleBalanceHistory(
    private val clock: Clock,
    private val specs: List<SampleAccountSpec>
) : BalanceHistoryRepository {
    private val samplesByAccount: Map<String, List<BalanceSample>> by lazy {
        specs.associate { spec -> spec.account.id to generate(spec, clock.instant(), clock.zone) }
    }
    private suspend fun samples(accountId: String): List<BalanceSample> =
        withContext(Dispatchers.Default) { samplesByAccount[accountId].orEmpty() }
    override suspend fun record(
        service: AiService,
        accountId: String,
        accountLabel: String,
        currency: String,
        totalBalance: Double
    ) = Unit
    override suspend fun latestSample(service: AiService, accountId: String): BalanceSample? {
        if (service != AiService.DEEPSEEK) return null
        return samples(accountId).lastOrNull()
    }
    override suspend fun consumption(
        service: AiService,
        accountId: String,
        granularity: ConsumptionGranularity
    ): List<ConsumptionPoint> {
        if (service != AiService.DEEPSEEK) return emptyList()
        val from = clock.instant().minus(Duration.ofDays(granularity.sampleWindowDays.toLong()))
        val window = samples(accountId)
            .filter { !it.timestamp.isBefore(from) }
            .map { BalanceConsumptionAggregator.Sample(it.totalBalance, it.currency, it.timestamp) }
        return when (granularity) {
            ConsumptionGranularity.DAILY -> BalanceConsumptionAggregator.daily(window, clock.zone)
            ConsumptionGranularity.MONTHLY -> BalanceConsumptionAggregator.monthly(window, clock.zone)
        }
    }
    companion object {
        val SAMPLE_STEP: Duration = Duration.ofHours(2)
        private const val FIRST_ACTIVE_HOUR = 8
        private const val LAST_ACTIVE_HOUR = 23
        private const val ACTIVE_SLOTS_PER_DAY = 8
        private const val WEEKEND_FACTOR = 0.3
        private const val TREND_START = 0.7
        private const val TREND_GROWTH = 0.6
        private const val SPIKE_PROBABILITY = 0.04
        private const val SPIKE_FACTOR = 3.5
        private const val OFFLINE_DAY_PROBABILITY = 0.02
        private val FORCED_OFFLINE_DAYS_AGO = 10..12
        fun generate(spec: SampleAccountSpec, now: Instant, zone: ZoneId): List<BalanceSample> {
            if (spec.historyStartDaysAgo <= 0) return emptyList()
            val random = Random(spec.seed)
            val end = now.minus(Duration.ofDays(spec.historyEndDaysAgo.toLong()))
            val start = now.minus(Duration.ofDays(spec.historyStartDaysAgo.toLong()))
            val totalDays = spec.historyStartDaysAgo.toDouble()
            val today = now.atZone(zone).toLocalDate()
            val dayAmounts = HashMap<Long, Double>()
            val offlineDays = HashMap<Long, Boolean>()
            var balance = spec.startBalance
            val samples = ArrayList<BalanceSample>()
            var t = start
            while (!t.isAfter(end)) {
                val local = t.atZone(zone)
                val date = local.toLocalDate()
                val daysAgo = ChronoUnit.DAYS.between(date, today)
                val dayAmount = dayAmounts.getOrPut(daysAgo) {
                    val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
                    val progress = 1.0 - daysAgo / totalDays
                    val trend = TREND_START + TREND_GROWTH * progress
                    val noise = 0.6 + random.nextDouble() * 0.8
                    val spike = if (random.nextDouble() < SPIKE_PROBABILITY) SPIKE_FACTOR else 1.0
                    spec.dailySpend * trend * noise * spike * (if (weekend) WEEKEND_FACTOR else 1.0)
                }
                val offline = offlineDays.getOrPut(daysAgo) {
                    daysAgo in FORCED_OFFLINE_DAYS_AGO || random.nextDouble() < OFFLINE_DAY_PROBABILITY
                }
                if (local.hour in FIRST_ACTIVE_HOUR..LAST_ACTIVE_HOUR) {
                    val slotNoise = 0.5 + random.nextDouble()
                    balance = (balance - dayAmount / ACTIVE_SLOTS_PER_DAY * slotNoise).coerceAtLeast(0.0)
                }
                if (balance < spec.topUpThreshold) {
                    balance += spec.topUpAmount
                }
                if (!offline) {
                    samples += BalanceSample(
                        accountId = spec.account.id,
                        accountLabel = spec.account.label,
                        currency = spec.currency,
                        totalBalance = balance.roundToCents(),
                        timestamp = t
                    )
                }
                t = t.plus(SAMPLE_STEP)
            }
            return samples
        }
        private fun Double.roundToCents(): Double = Math.round(this * 100) / 100.0
    }
}
