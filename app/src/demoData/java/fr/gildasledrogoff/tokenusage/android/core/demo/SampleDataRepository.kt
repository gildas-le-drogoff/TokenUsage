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
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendAmount
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageBreakdownRow
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageSeverity
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoQuota
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
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

    override val history: BalanceHistoryRepository = SampleBalanceHistory(clock, localizedSpecs())

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
            DemoQuota(AiService.CLAUDE, context.getString(R.string.demo_claude_personal), Result.Success(claude(now))),
            DemoQuota(AiService.CLAUDE, context.getString(R.string.demo_claude_work), Result.Success(claudeWork(now))),
            DemoQuota(AiService.CODEX, context.getString(R.string.demo_codex_personal), Result.Success(codex(now))),
            DemoQuota(AiService.CODEX, context.getString(R.string.demo_codex_work), Result.Success(codexWork(now))),
            DemoQuota(AiService.GEMINI, null, Result.Success(gemini(now))),
        ) + deepseekQuotas(now) + listOf(
            DemoQuota(AiService.ZAI, context.getString(R.string.demo_zai_personal), Result.Success(zai(now))),
            DemoQuota(AiService.ZAI, context.getString(R.string.demo_zai_work), Result.Success(zaiWork(now))),
            DemoQuota(AiService.COPILOT, context.getString(R.string.demo_copilot_personal), Result.Success(copilot(now))),
            DemoQuota(AiService.COPILOT, context.getString(R.string.demo_copilot_work), Result.Success(copilotWork(now))),
            DemoQuota(AiService.OPENROUTER, null, Result.Success(openRouter(now))),
            DemoQuota(AiService.OPENAI, null, Result.Success(openAi(now)))
        )
    }

    private fun claude(now: Instant) = QuotaInfo(
        service = AiService.CLAUDE,
        windows = listOf(
            UsageWindow("5-Hour", 0.42, now + Duration.ofMinutes(133)),
            UsageWindow("7-Day", 0.78, now + Duration.ofDays(3).plusHours(5)),
            UsageWindow("Opus", 0.25, now + Duration.ofDays(3).plusHours(5))
                    ),
        extraUsage = null,
        tier = "Max 20x",
        weeklyBreakdown = listOf(
            UsageBreakdownRow("Claude Code", 82.0),
            // UsageBreakdownRow("Claude.ai", 15.0),
            UsageBreakdownRow("Claude Design", 3.0)
        ),
        fetchedAt = now
    )

    private fun claudeWork(now: Instant) = QuotaInfo(
        service = AiService.CLAUDE,
        windows = listOf(
            UsageWindow("5-Hour", 0.08, now + Duration.ofMinutes(271)),
            UsageWindow("7-Day", 0.31, now + Duration.ofDays(5).plusHours(11))
        ),
        tier = "Pro",
        fetchedAt = now
    )

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
            UsageWindow("Flash", 0.60, now + Duration.ofHours(9))
        ),
        extraUsage = null,
        tier = "Free",
        fetchedAt = now
    )

    /** One card per DeepSeek account, like every other service. */
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
            UsageWindow("Session (5h)", 0.33, now + Duration.ofMinutes(208)),
            UsageWindow("Monthly Tools", 0.6, now + Duration.ofDays(12))
                .takeUnless { prefsManager.isZaiMonthlyToolsHidden() },
            UsageWindow("Monthly Quota", 0.27, now + Duration.ofDays(12))
        ),
        extraUsage = null,
        tier = "Pro",
        fetchedAt = now
    )

    private fun zaiWork(now: Instant) = QuotaInfo(
        service = AiService.ZAI,
        windows = listOfNotNull(
            UsageWindow("Session (5h)", 0.08, now + Duration.ofMinutes(41)),
            UsageWindow("Monthly Tools", 0.15, now + Duration.ofDays(20))
                .takeUnless { prefsManager.isZaiMonthlyToolsHidden() },
            UsageWindow("Monthly Quota", 0.27, now + Duration.ofDays(20))
        ),
        extraUsage = null,
        tier = "Lite",
        fetchedAt = now
    )

    private fun copilot(now: Instant) = QuotaInfo(
        service = AiService.COPILOT,
        windows = listOf(
            UsageWindow("Premium requests", 0.51, now + Duration.ofDays(16)),
            UsageWindow("Chat", 0.12, now + Duration.ofDays(16))
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

    companion object {
        private const val PREFS_NAME = "tokenusage_debug_demo"
        private const val KEY_ENABLED = "demo_enabled"
        private const val SIMULATED_LATENCY_MS = 800L
        private const val GRANTED_BONUS = 10.0

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
