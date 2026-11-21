package fr.gildasledrogoff.tokenusage.core.demo
import android.content.Context
import androidx.core.content.edit
import androidx.annotation.StringRes
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AccountBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.CreditBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.DeepseekAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.ExtraUsage
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendAmount
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageBreakdownRow
import fr.gildasledrogoff.tokenusage.core.domain.model.UsagePace
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageSeverity
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoQuota
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.status.ProviderStatus
import fr.gildasledrogoff.tokenusage.core.status.StatusIndicator
import fr.gildasledrogoff.tokenusage.core.status.statusSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import fr.gildasledrogoff.tokenusage.core.alert.TimedValue
import fr.gildasledrogoff.tokenusage.core.data.WINDOW_5H
import fr.gildasledrogoff.tokenusage.core.data.WINDOW_7D
import kotlin.random.Random
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class SampleDataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
    private val prefsManager: EncryptedPrefsManager
) : DemoDataRepository {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    override val isAvailable: Boolean = true
    override val supportedServices: Set<AiService> = setOf(
        AiService.CLAUDE,
        AiService.CLAUDE_PLATFORM,
        AiService.CODEX,
        AiService.GEMINI,
        AiService.DEEPSEEK,
        AiService.ZAI,
        AiService.COPILOT,
        AiService.OPENROUTER,
        AiService.OPENAI
    )
    override fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)
    override fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
    }
    private val sampleHistory = SampleBalanceHistory(clock, localizedSpecs())
    override val history: BalanceHistoryRepository = sampleHistory
    override suspend fun sampleBalanceSeries(
        service: AiService,
        accountId: String,
        since: Instant
    ): List<TimedValue> = when {
        service == AiService.DEEPSEEK -> sampleHistory.series(accountId, since)
        service == AiService.CLAUDE_PLATFORM && accountId == ID_CLAUDE_PLATFORM ->
            claudePlatformBalanceSeries(clock.instant()).filter { !it.at.isBefore(since) }
        else -> emptyList()
    }
    private fun claudePlatformBalanceSeries(now: Instant): List<TimedValue> {
        val random = Random(CLAUDE_PLATFORM_SEED)
        val steps = Duration.ofDays(7).toHours().toInt()
        var balance = CLAUDE_PLATFORM_BALANCE
        val series = ArrayList<TimedValue>(steps + 1)
        series += TimedValue(balance, now)
        for (hoursAgo in 1..steps) {
            val dailySpend = if (hoursAgo <= 24) 3.1 else 1.8
            balance += dailySpend / 24.0 * (0.6 + random.nextDouble() * 0.8)
            if (hoursAgo == CLAUDE_PLATFORM_TOP_UP_HOURS_AGO) balance -= CLAUDE_PLATFORM_TOP_UP
            series += TimedValue(Math.round(balance * 100) / 100.0, now.minus(Duration.ofHours(hoursAgo.toLong())))
        }
        return series.asReversed()
    }
    override fun sampleDeepseekAccounts(): List<DeepseekAccount> = localizedSpecs().map { it.account }
    private fun localizedSpecs(): List<SampleAccountSpec> = DEEPSEEK_SPECS.map { spec ->
        spec.copy(account = spec.account.copy(label = context.getString(labelRes(spec.account.id))))
    }
    private fun labelRes(id: String): Int = when (id) {
        ID_MAIN -> R.string.demo_account_main
        ID_USD -> R.string.demo_account_usd
        ID_NEW -> R.string.demo_account_new
        else -> R.string.demo_account_old
    }
    override suspend fun sampleQuotas(): List<DemoQuota> {
        delay(SIMULATED_LATENCY_MS)
        val now = clock.instant()
        return listOf(
            DemoQuota(AiService.CLAUDE, context.getString(R.string.demo_claude_personal), Result.Success(claude(now)), accountId = ID_CLAUDE_PERSONAL),
            DemoQuota(AiService.CLAUDE, context.getString(R.string.demo_claude_work), Result.Success(claudeWork(now)), accountId = ID_CLAUDE_WORK),
            DemoQuota(AiService.CLAUDE_PLATFORM, context.getString(R.string.demo_claude_platform), Result.Success(claudePlatform(now)), accountId = ID_CLAUDE_PLATFORM),
            DemoQuota(AiService.CODEX, context.getString(R.string.demo_codex_personal), Result.Success(codex(now))),
            DemoQuota(AiService.CODEX, context.getString(R.string.demo_codex_work), Result.Success(codexWork(now))),
            DemoQuota(AiService.GEMINI, null, Result.Success(gemini(now))),
        ) + deepseekQuotas(now) + listOf(
            DemoQuota(AiService.ZAI, context.getString(R.string.demo_zai_personal), Result.Success(zai(now))),
            DemoQuota(AiService.ZAI, context.getString(R.string.demo_zai_work), Result.Success(zaiWork(now))),
            DemoQuota(AiService.COPILOT, context.getString(R.string.demo_copilot_personal), Result.Success(copilot(now))),
            DemoQuota(
                AiService.COPILOT,
                context.getString(R.string.demo_copilot_work),
                Result.Failure(AppError.ServiceUnavailable),
                fallback = copilotWork(now.minus(COPILOT_WORK_STALE_FOR))
            ),
            DemoQuota(AiService.OPENROUTER, null, Result.Success(openRouter(now))),
            DemoQuota(AiService.OPENAI, null, Result.Success(openAi(now)))
        )
    }
    private fun claude(now: Instant): QuotaInfo {
        val weeklyReset = now + CLAUDE_PERSONAL_7D.remaining
        return QuotaInfo(
            service = AiService.CLAUDE,
            windows = listOf(
                CLAUDE_PERSONAL_5H.window(WINDOW_5H, now, SESSION_MINUTES),
                CLAUDE_PERSONAL_7D.window(WINDOW_7D, now, WEEKLY_MINUTES),
                UsageWindow("Sonnet", 0.47, weeklyReset, windowMinutes = WEEKLY_MINUTES),
                UsageWindow("Opus", 0.25, weeklyReset, windowMinutes = WEEKLY_MINUTES),
                UsageWindow("Claude Design", 0.12, weeklyReset, windowMinutes = WEEKLY_MINUTES)
            ),
            extraUsage = ExtraUsage(
                isEnabled = true,
                monthlyLimit = 50.0,
                usedCredits = 12.40,
                utilization = 12.40 / 50.0,
                currency = "USD"
            ),
            tier = "Max 20x",
            weeklyBreakdown = listOf(
                UsageBreakdownRow("Claude Code", 82.0),
                UsageBreakdownRow("Claude Design", 3.0)
            ),
            fetchedAt = now
        )
    }
    private fun claudeWork(now: Instant) = QuotaInfo(
        service = AiService.CLAUDE,
        windows = listOf(
            CLAUDE_WORK_5H.window(WINDOW_5H, now, SESSION_MINUTES, UsageSeverity.WARNING),
            CLAUDE_WORK_7D.window(WINDOW_7D, now, WEEKLY_MINUTES)
        ),
        tier = "Pro",
        fetchedAt = now
    )
    private fun claudePlatform(now: Instant) = QuotaInfo(
        service = AiService.CLAUDE_PLATFORM,
        windows = listOf(
            UsageWindow("API credit", 0.35, now + Duration.ofDays(18)),
            UsageWindow("Purchased credit", 0.62, now + Duration.ofDays(210))
        ),
        extraUsage = null,
        tier = "Acme Labs",
        accountBalances = listOf(
            AccountBalance(
                label = "",
                balance = CreditBalance("USD", CLAUDE_PLATFORM_BALANCE, grantedBalance = null, toppedUpBalance = null, isAvailable = true)
            )
        ),
        spending = listOf(SpendAmount(SpendPeriod.THIS_MONTH, 37.80, "USD")),
        fetchedAt = now
    )
    override fun sampleUtilizationSeries(
        accountId: String,
        windowLabel: String,
        from: Instant,
        to: Instant
    ): List<TimedValue> {
        val window = when (accountId to windowLabel) {
            ID_CLAUDE_PERSONAL to WINDOW_5H -> CLAUDE_PERSONAL_5H
            ID_CLAUDE_PERSONAL to WINDOW_7D -> CLAUDE_PERSONAL_7D
            ID_CLAUDE_WORK to WINDOW_5H -> CLAUDE_WORK_5H
            ID_CLAUDE_WORK to WINDOW_7D -> CLAUDE_WORK_7D
            else -> return emptyList()
        }
        val current = window.utilization
        val remaining = window.remaining
        val period = if (windowLabel == WINDOW_5H) Duration.ofHours(5) else Duration.ofDays(7)
        val resetAt = to + remaining
        val random = Random(accountId.hashCode() * 31L + windowLabel.hashCode())
        val peaks = HashMap<Long, Double>()
        fun shapeAt(at: Instant): Pair<Long, Double> {
            val untilReset = Duration.between(at, resetAt)
            val cycle = Math.floorDiv(untilReset.toMillis(), period.toMillis())
            val elapsed = 1.0 - (untilReset.toMillis() - cycle * period.toMillis()).toDouble() / period.toMillis()
            return cycle to Math.pow(elapsed, 0.8)
        }
        val currentPeak = current / shapeAt(to).second.coerceAtLeast(0.05)
        val series = ArrayList<TimedValue>()
        var t = from
        while (t.isBefore(to)) {
            val (cycle, shape) = shapeAt(t)
            val peak = if (cycle == 0L) currentPeak else
                peaks.getOrPut(cycle) { 0.45 + random.nextDouble() * 0.5 }
            series += TimedValue((peak * shape * 100.0).coerceIn(0.0, 100.0), t)
            t = t.plus(CURVE_STEP)
        }
        if (!to.isBefore(from)) series += TimedValue(current * 100.0, to)
        return series
    }
    private fun codex(now: Instant) = QuotaInfo(
        service = AiService.CODEX,
        windows = listOf(
            UsageWindow("5-Hour", 0.18, now + Duration.ofMinutes(47)),
            UsageWindow("7-Day", 0.55, now + Duration.ofDays(5).plusHours(2))
        ),
        extraUsage = null,
        tier = "Plus",
        fetchedAt = now
    )
    private fun codexWork(now: Instant) = QuotaInfo(
        service = AiService.CODEX,
        windows = listOf(
            UsageWindow("5-Hour", 0.64, now + Duration.ofMinutes(112)),
            UsageWindow("7-Day", 0.21, now + Duration.ofDays(2).plusHours(9))
        ),
        extraUsage = null,
        tier = "Pro",
        fetchedAt = now
    )
    private fun gemini(now: Instant) = QuotaInfo(
        service = AiService.GEMINI,
        windows = listOf(
            UsageWindow("Pro", 0.45, now + Duration.ofHours(9)),
            UsageWindow("Flash", 0.60, now + Duration.ofHours(9)),
            UsageWindow("Flash Lite", 0.08, now + Duration.ofHours(9))
        ),
        extraUsage = null,
        tier = "Free",
        fetchedAt = now
    )
    
    private suspend fun deepseekQuotas(now: Instant): List<DemoQuota> = localizedSpecs().map { spec ->
        val account = spec.account
        val result: Result<QuotaInfo, AppError> = when (account.id) {
            ID_OLD -> Result.Failure(AppError.AuthError(AiService.DEEPSEEK, isTerminal = true))
            ID_NEW -> Result.Success(
                deepseekQuota(now, account, CreditBalance(spec.currency, 0.0, 0.0, 0.0, isAvailable = false))
            )
            else -> {
                val total = history.latestSample(AiService.DEEPSEEK, account.id)?.totalBalance
                    ?: spec.startBalance
                val granted = minOf(GRANTED_BONUS, total)
                Result.Success(
                    deepseekQuota(
                        now, account,
                        CreditBalance(
                            currency = spec.currency,
                            totalBalance = total,
                            grantedBalance = granted,
                            toppedUpBalance = total - granted,
                            isAvailable = true
                        )
                    )
                )
            }
        }
        DemoQuota(AiService.DEEPSEEK, account.label, result, accountId = account.id)
    }
    private fun deepseekQuota(now: Instant, account: DeepseekAccount, balance: CreditBalance) = QuotaInfo(
        service = AiService.DEEPSEEK,
        windows = emptyList(),
        extraUsage = null,
        accountBalances = listOf(AccountBalance(label = account.label, balance = balance)),
        fetchedAt = now,
        accountId = account.id,
        accountLabel = account.label
    )
    private fun zai(now: Instant) = QuotaInfo(
        service = AiService.ZAI,
        windows = listOfNotNull(
            UsageWindow("Session (5h)", 0.33, now + Duration.ofMinutes(208), windowMinutes = SESSION_MINUTES),
            monthlyWindow("Monthly Tools", 0.6, now + Duration.ofDays(12))
                .takeUnless { prefsManager.isZaiMonthlyToolsHidden() },
            monthlyWindow("Monthly Quota", 0.27, now + Duration.ofDays(12))
        ),
        extraUsage = null,
        tier = "Pro",
        fetchedAt = now
    )
    private fun zaiWork(now: Instant) = QuotaInfo(
        service = AiService.ZAI,
        windows = listOfNotNull(
            UsageWindow("Session (5h)", 0.08, now + Duration.ofMinutes(41), windowMinutes = SESSION_MINUTES),
            monthlyWindow("Monthly Tools", 0.15, now + Duration.ofDays(20))
                .takeUnless { prefsManager.isZaiMonthlyToolsHidden() },
            monthlyWindow("Monthly Quota", 0.27, now + Duration.ofDays(20))
        ),
        extraUsage = null,
        tier = "Lite",
        fetchedAt = now
    )
    private fun monthlyWindow(label: String, utilization: Double, resetsAt: Instant) =
        UsageWindow(label, utilization, resetsAt, windowMinutes = UsagePace.monthlyWindowMinutes(resetsAt))
    private fun copilot(now: Instant) = QuotaInfo(
        service = AiService.COPILOT,
        windows = listOf(
            UsageWindow("Premium requests", 0.51, now + Duration.ofDays(16)),
            UsageWindow("Chat", 0.12, now + Duration.ofDays(16)),
            UsageWindow("Completions", 0.07, now + Duration.ofDays(16))
        ),
        extraUsage = null,
        tier = "Individual",
        fetchedAt = now
    )
    private fun copilotWork(now: Instant) = QuotaInfo(
        service = AiService.COPILOT,
        windows = listOf(
            UsageWindow("Premium requests", 0.23, now + Duration.ofDays(9)),
            UsageWindow("Chat", 0.05, now + Duration.ofDays(9))
        ),
        extraUsage = null,
        tier = "Business",
        fetchedAt = now
    )
    private fun openRouter(now: Instant) = QuotaInfo(
        service = AiService.OPENROUTER,
        windows = listOf(UsageWindow("API key limit", 0.42, now + Duration.ofDays(3))),
        extraUsage = null,
        accountBalances = listOf(
            AccountBalance(
                label = "",
                balance = CreditBalance("USD", 18.35, grantedBalance = null, toppedUpBalance = 25.0, isAvailable = true)
            )
        ),
        spending = listOf(
            SpendAmount(SpendPeriod.TODAY, 0.84, "USD"),
            SpendAmount(SpendPeriod.THIS_WEEK, 4.21, "USD"),
            SpendAmount(SpendPeriod.THIS_MONTH, 6.65, "USD")
        ),
        fetchedAt = now
    )
    private fun openAi(now: Instant) = QuotaInfo(
        service = AiService.OPENAI,
        windows = emptyList(),
        extraUsage = null,
        tier = "Admin API",
        spending = listOf(
            SpendAmount(SpendPeriod.TODAY, 1.37, "USD"),
            SpendAmount(SpendPeriod.LAST_7_DAYS, 12.9, "USD"),
            SpendAmount(SpendPeriod.LAST_30_DAYS, 48.62, "USD")
        ),
        fetchedAt = now
    )
    override fun sampleProviderStatuses(): Map<AiService, ProviderStatus> {
        val now = clock.instant()
        fun status(service: AiService, indicator: StatusIndicator, description: String?, updatedAgo: Duration) =
            service.statusSource()?.let { source ->
                service to ProviderStatus(indicator, description, now.minus(updatedAgo), source.pageUrl)
            }
        return listOfNotNull(
            status(AiService.CLAUDE, StatusIndicator.MINOR, "Elevated errors on Claude Opus", Duration.ofMinutes(25)),
            status(AiService.CLAUDE_PLATFORM, StatusIndicator.MINOR, "Elevated errors on Claude Opus", Duration.ofMinutes(25)),
            status(AiService.CODEX, StatusIndicator.NONE, "All Systems Operational", Duration.ofHours(6)),
            status(AiService.OPENAI, StatusIndicator.NONE, "All Systems Operational", Duration.ofHours(6)),
            status(AiService.COPILOT, StatusIndicator.MAJOR, "Disruption with some GitHub services", Duration.ofMinutes(12)),
            status(AiService.OPENROUTER, StatusIndicator.NONE, null, Duration.ofDays(2)),
            status(AiService.DEEPSEEK, StatusIndicator.NONE, null, Duration.ofDays(4))
        ).toMap()
    }
    private data class ClaudeWindowSpec(val utilization: Double, val remaining: Duration) {
        fun window(label: String, now: Instant, minutes: Int, severity: UsageSeverity = UsageSeverity.NORMAL) =
            UsageWindow(label, utilization, now + remaining, severity, windowMinutes = minutes)
    }
    companion object {
        private val CLAUDE_PERSONAL_5H = ClaudeWindowSpec(0.42, Duration.ofMinutes(133))
        private val CLAUDE_PERSONAL_7D = ClaudeWindowSpec(0.86, Duration.ofDays(3).plusHours(5))
        private val CLAUDE_WORK_5H = ClaudeWindowSpec(0.71, Duration.ofMinutes(54))
        private val CLAUDE_WORK_7D = ClaudeWindowSpec(0.31, Duration.ofDays(5).plusHours(11))
        private val COPILOT_WORK_STALE_FOR: Duration = Duration.ofMinutes(160)
        private const val PREFS_NAME = "tokenusage_debug_demo"
        private const val KEY_ENABLED = "demo_enabled"
        private const val SIMULATED_LATENCY_MS = 800L
        private const val GRANTED_BONUS = 10.0
        private val CURVE_STEP: Duration = Duration.ofMinutes(30)
        private const val ID_CLAUDE_PERSONAL = "demo-claude-personal"
        private const val ID_CLAUDE_WORK = "demo-claude-work"
        private const val ID_CLAUDE_PLATFORM = "demo-claude-platform"
        private const val CLAUDE_PLATFORM_BALANCE = 64.20
        private const val CLAUDE_PLATFORM_TOP_UP = 50.0
        private const val CLAUDE_PLATFORM_TOP_UP_HOURS_AGO = 96
        private const val CLAUDE_PLATFORM_SEED = 2024L
        private const val SESSION_MINUTES = 5 * 60
        private const val WEEKLY_MINUTES = 7 * 24 * 60
        private const val ID_MAIN = "demo-deepseek-main"
        private const val ID_USD = "demo-deepseek-usd"
        private const val ID_NEW = "demo-deepseek-new"
        private const val ID_OLD = "demo-deepseek-old"
        val DEEPSEEK_SPECS = listOf(
            SampleAccountSpec(
                account = DeepseekAccount(ID_MAIN, "Personal", apiKey = ""),
                currency = "CNY",
                startBalance = 300.0,
                dailySpend = 6.0,
                topUpThreshold = 30.0,
                topUpAmount = 300.0,
                historyStartDaysAgo = 395,
                seed = 42L
            ),
            SampleAccountSpec(
                account = DeepseekAccount(ID_USD, "Pro (USD)", apiKey = ""),
                currency = "USD",
                startBalance = 50.0,
                dailySpend = 0.9,
                topUpThreshold = 5.0,
                topUpAmount = 50.0,
                historyStartDaysAgo = 75,
                seed = 7L
            ),
            SampleAccountSpec(
                account = DeepseekAccount(ID_NEW, "New account", apiKey = ""),
                currency = "CNY",
                startBalance = 0.0,
                dailySpend = 0.0,
                topUpThreshold = 0.0,
                topUpAmount = 0.0,
                historyStartDaysAgo = 0,
                seed = 0L
            ),
            SampleAccountSpec(
                account = DeepseekAccount(ID_OLD, "Old account", apiKey = ""),
                currency = "CNY",
                startBalance = 120.0,
                dailySpend = 3.0,
                topUpThreshold = 15.0,
                topUpAmount = 100.0,
                historyStartDaysAgo = 380,
                historyEndDaysAgo = 200,
                seed = 1337L
            )
        )
    }
}
