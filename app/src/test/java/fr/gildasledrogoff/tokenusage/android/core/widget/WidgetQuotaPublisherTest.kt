package fr.gildasledrogoff.tokenusage.core.widget

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.core.domain.model.AccountBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.CreditBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendAmount
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    application = Application::class,
    qualifiers = "en"
)
class WidgetQuotaPublisherTest {
    private lateinit var context: Context
    private lateinit var prefs: WidgetPrefsManager
    private val rates = mutableMapOf<String, FxRate>()
    private val defaultLocale = Locale.getDefault()

    private val exchangeRates = object : ExchangeRateRepository {
        override suspend fun displayRate(currency: String): FxRate? = rates[currency]
        override suspend fun fetchLatest(
            source: FxRateSource,
            from: String,
            to: String
        ): Result<FxRate, AppError> = error("unused")

        override fun cachedRate(source: FxRateSource, from: String, to: String): FxRate? = null
    }

    private val fetchedAt = Instant.parse("2026-11-21T10:00:00Z")

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
        context = ApplicationProvider.getApplicationContext()
        prefs = WidgetPrefsManager(context)
    }

    @After
    fun tearDown() = Locale.setDefault(defaultLocale)

    private fun publisher() = WidgetQuotaPublisher(context, prefs, exchangeRates)

    private fun quota(
        service: AiService = AiService.DEEPSEEK,
        balances: List<AccountBalance> = emptyList(),
        spending: List<SpendAmount> = emptyList(),
        windows: List<UsageWindow> = emptyList()
    ) = QuotaInfo(
        service = service,
        windows = windows,
        tier = "Tier",
        accountBalances = balances,
        spending = spending,
        fetchedAt = fetchedAt
    )

    private fun balance(label: String, amount: Double?, currency: String = "USD", error: AppError? = null) =
        AccountBalance(
            label = label,
            balance = amount?.let { CreditBalance(currency, it, null, null, true) },
            error = error
        )

    @Test
    fun `publishing nothing leaves the cache untouched`() = runTest {
        prefs.cacheAccounts(AiService.CLAUDE, listOf(CachedAccount(tier = "Keep")))

        publisher().publish(emptyList())

        assertEquals("Keep", prefs.getCachedAccounts(AiService.CLAUDE).single().tier)
    }

    @Test
    fun `a fresh quota is cached with windows and fetch date`() = runTest {
        val resetsAt = Instant.parse("2026-11-21T15:00:00Z")
        val entry = WidgetAccountQuota(
            AiService.CLAUDE,
            accountLabel = "Work",
            accountNumber = 1,
            quota = quota(
                service = AiService.CLAUDE,
                windows = listOf(UsageWindow("5-Hour", 0.25, resetsAt), UsageWindow("7-Day", 0.5, null))
            )
        )

        publisher().publish(listOf(entry))

        val cached = prefs.getCachedAccounts(AiService.CLAUDE).single()
        assertEquals("Work", cached.accountLabel)
        assertEquals(1, cached.accountNumber)
        assertEquals("Tier", cached.tier)
        assertEquals(
            listOf(CachedWindow("5-Hour", 0.25f, resetsAt.epochSecond), CachedWindow("7-Day", 0.5f, null)),
            cached.windows
        )
        assertEquals(fetchedAt.toEpochMilli(), cached.dataFetchedAtEpochMillis)
        assertEquals(WidgetDataStatus.FRESH, cached.status)
        assertNull(cached.balanceText)
    }

    @Test
    fun `balances are formatted in their own currency without a display rate`() = runTest {
        val entry = WidgetAccountQuota(
            AiService.DEEPSEEK,
            quota = quota(balances = listOf(balance("Main", 12.5, "CNY"), balance("", 1.0)))
        )

        publisher().publish(listOf(entry))

        assertEquals(
            "Main: 12.50 CNY | Account: 1.00 USD",
            prefs.getCachedAccounts(AiService.DEEPSEEK).single().balanceText
        )
    }

    @Test
    fun `balances are converted when a display rate exists`() = runTest {
        rates["USD"] = FxRate("USD", "EUR", 0.5, FxRateSource.MANUAL)
        val entry = WidgetAccountQuota(AiService.DEEPSEEK, quota = quota(balances = listOf(balance("Main", 10.0))))

        publisher().publish(listOf(entry))

        assertEquals("Main: ≈ 5.00 EUR", prefs.getCachedAccounts(AiService.DEEPSEEK).single().balanceText)
    }

    @Test
    fun `a balance in error or without amount is still listed`() = runTest {
        val entry = WidgetAccountQuota(
            AiService.DEEPSEEK,
            quota = quota(
                balances = listOf(
                    balance("Broken", null, error = AppError.RateLimited),
                    balance("Empty", null)
                )
            )
        )

        publisher().publish(listOf(entry))

        assertEquals(
            "Broken: error | Empty: -",
            prefs.getCachedAccounts(AiService.DEEPSEEK).single().balanceText
        )
    }

    @Test
    fun `spending is used when there is no balance`() = runTest {
        val entry = WidgetAccountQuota(
            AiService.OPENAI,
            quota = quota(
                service = AiService.OPENAI,
                spending = listOf(
                    SpendAmount(SpendPeriod.TODAY, 1.0, "USD"),
                    SpendAmount(SpendPeriod.THIS_MONTH, 20.0, "USD")
                )
            )
        )

        publisher().publish(listOf(entry))

        assertEquals(
            "Today: 1.00 USD | This month: 20.00 USD",
            prefs.getCachedAccounts(AiService.OPENAI).single().balanceText
        )
    }

    @Test
    fun `a failed account keeps its previous data marked as failed`() = runTest {
        prefs.cacheAccounts(
            AiService.CODEX,
            listOf(CachedAccount(accountLabel = "Work", accountNumber = 1, tier = "Plus", dataFetchedAtEpochMillis = 5L))
        )
        val entry = WidgetAccountQuota(
            AiService.CODEX,
            accountLabel = "Work",
            accountNumber = 1,
            quota = null,
            error = AppError.AuthError(AiService.CODEX, isTerminal = false)
        )

        publisher().publish(listOf(entry))

        val cached = prefs.getCachedAccounts(AiService.CODEX).single()
        assertEquals("Plus", cached.tier)
        assertEquals(WidgetDataStatus.FAILED, cached.status)
    }

    @Test
    fun `an unknown failed account becomes an offline placeholder`() = runTest {
        val entry = WidgetAccountQuota(AiService.GEMINI, accountLabel = "Perso", quota = null)

        publisher().publish(listOf(entry), offline = true)

        assertEquals(
            CachedAccount(accountLabel = "Perso", status = WidgetDataStatus.OFFLINE),
            prefs.getCachedAccounts(AiService.GEMINI).single()
        )
    }

    @Test
    fun `entries are cached per service`() = runTest {
        publisher().publish(
            listOf(
                WidgetAccountQuota(AiService.CLAUDE, accountNumber = 1, quota = quota(AiService.CLAUDE)),
                WidgetAccountQuota(AiService.CLAUDE, accountNumber = 2, quota = quota(AiService.CLAUDE)),
                WidgetAccountQuota(AiService.ZAI, quota = quota(AiService.ZAI))
            )
        )

        assertEquals(listOf(1, 2), prefs.getCachedAccounts(AiService.CLAUDE).map { it.accountNumber })
        assertEquals(1, prefs.getCachedAccounts(AiService.ZAI).size)
    }
}
