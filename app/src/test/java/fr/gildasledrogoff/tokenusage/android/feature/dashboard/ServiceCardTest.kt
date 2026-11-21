package fr.gildasledrogoff.tokenusage.feature.dashboard

import android.app.Application
import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageSeverity
import fr.gildasledrogoff.tokenusage.ui.theme.TokenUsageTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
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
    qualifiers = "en-w411dp-h891dp"
)
class ServiceCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val defaultLocale = Locale.getDefault()

    @Before
    fun setUp() = Locale.setDefault(Locale.US)

    @After
    fun tearDown() = Locale.setDefault(defaultLocale)

    private fun card(
        service: AiService = AiService.CLAUDE,
        windows: List<UsageWindowUi> = emptyList(),
        tier: String? = null,
        balances: List<AccountBalanceUi> = emptyList(),
        breakdown: List<UsageBreakdownRowUi> = emptyList(),
        spending: List<SpendRowUi> = emptyList(),
        extra: ExtraUsageUi? = null,
        label: String? = null,
        number: Int? = null,
        error: AppError? = null,
        staleSince: Instant? = null
    ) = ServiceCardData(
        service = service,
        windows = windows,
        extraUsage = extra,
        tier = tier,
        accountBalances = balances,
        weeklyBreakdown = breakdown,
        spending = spending,
        accountLabel = label,
        accountNumber = number,
        error = error,
        staleSince = staleSince,
        showPace = false
    )

    private fun show(
        data: ServiceCardData,
        mode: QuotaDisplayMode = QuotaDisplayMode.REMAINING,
        onToggle: (() -> Unit)? = null,
        expanded: Boolean = false
    ) = compose.setContent {
        TokenUsageTheme(themeMode = ThemeMode.LIGHT, quotaDisplayMode = mode, dynamicColor = false) {
            ServiceCard(data, expanded = expanded, onToggleExpand = onToggle) {
                Text("Expanded details")
            }
        }
    }

    private fun balance(
        label: String = "Main",
        total: Double = 12.5,
        currency: String = "USD",
        granted: Double? = null,
        toppedUp: Double? = null,
        available: Boolean = true,
        rate: FxRate? = null,
        error: AppError? = null
    ) = AccountBalanceUi(label, currency, total, granted, toppedUp, available, rate, error = error)

    @Test
    fun `header shows service, tier and account label`() {
        show(card(tier = "Max", label = "Work"))

        compose.onNodeWithText("Claude").assertIsDisplayed()
        compose.onNodeWithText("Max").assertIsDisplayed()
        compose.onNodeWithText("Work").assertIsDisplayed()
    }

    @Test
    fun `an unnamed account shows its number`() {
        show(card(number = 2))

        compose.onNodeWithText("Account 2").assertIsDisplayed()
    }

    @Test
    fun `windows are described with remaining share and status`() {
        show(
            card(
                windows = listOf(
                    UsageWindowUi("5-Hour", 0.3),
                    UsageWindowUi("7-Day", 0.7),
                    UsageWindowUi("Weekly scoped", 0.9)
                )
            )
        )

        compose.onNodeWithContentDescription("5-Hour: 70% left, OK").assertExists()
        compose.onNodeWithContentDescription("7-Day: 30% left, Low").assertExists()
        compose.onNodeWithContentDescription("Weekly scoped: 10% left, Critical", substring = true).assertExists()
        compose.onNodeWithText("Critical").assertIsDisplayed()
    }

    @Test
    fun `used mode describes the used share`() {
        show(card(windows = listOf(UsageWindowUi("5-Hour", 0.3))), mode = QuotaDisplayMode.USED)

        compose.onNodeWithContentDescription("5-Hour: 30% used, OK").assertExists()
    }

    @Test
    fun `severity raises the status even with quota left`() {
        show(card(windows = listOf(UsageWindowUi("5-Hour", 0.1, severity = UsageSeverity.WARNING))))

        compose.onNodeWithContentDescription("5-Hour: 90% left, Low").assertExists()
    }

    @Test
    fun `an error without data shows the message and a critical status`() {
        show(card(error = AppError.RateLimited))

        compose.onNodeWithText("Rate limited").assertIsDisplayed()
        compose.onNodeWithText("Critical").assertIsDisplayed()
    }

    @Test
    fun `stale data shows the failure reason next to the old values`() {
        show(
            card(
                windows = listOf(UsageWindowUi("5-Hour", 0.3)),
                error = AppError.RateLimited,
                staleSince = Instant.parse("2026-11-20T10:00:00Z")
            )
        )

        compose.onNodeWithText("Refresh failed: Rate limited").assertIsDisplayed()
        compose.onNodeWithText("Data from", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("5-Hour: 70% left, OK").assertExists()
    }

    @Test
    fun `extra credits are shown with their currency`() {
        show(card(extra = ExtraUsageUi(monthlyLimit = 50.0, usedCredits = 10.0, utilization = 0.2, currency = "USD")))

        compose.onNodeWithContentDescription("Extra credits: 80% left, OK").assertExists()
    }

    @Test
    fun `weekly breakdown hides rows under one percent`() {
        show(
            card(
                breakdown = listOf(
                    UsageBreakdownRowUi("Opus", 42.0),
                    UsageBreakdownRowUi("Haiku", 0.4)
                )
            )
        )

        compose.onNodeWithText("Usage this week").assertIsDisplayed()
        compose.onNodeWithText("Opus").assertIsDisplayed()
        compose.onNodeWithText("42%").assertIsDisplayed()
        compose.onNodeWithText("Haiku").assertDoesNotExist()
    }

    @Test
    fun `balances list each account with its amount`() {
        show(
            card(
                service = AiService.DEEPSEEK,
                balances = listOf(balance("Main", 12.5), balance("", 3.0, "CNY"))
            )
        )

        compose.onNodeWithText("Remaining credit").assertIsDisplayed()
        compose.onNodeWithText("Main").assertIsDisplayed()
        compose.onNodeWithText("12.50 USD").assertIsDisplayed()
        compose.onNodeWithText("Account").assertIsDisplayed()
        compose.onNodeWithText("3.00 CNY").assertIsDisplayed()
    }

    @Test
    fun `a converted balance shows the approximate value`() {
        show(
            card(
                service = AiService.DEEPSEEK,
                balances = listOf(balance(total = 10.0, rate = FxRate("USD", "EUR", 0.5, FxRateSource.MANUAL)))
            )
        )

        compose.onNodeWithText("≈ 5.00 EUR", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an unavailable balance is flagged`() {
        show(card(service = AiService.DEEPSEEK, balances = listOf(balance(total = 0.0, available = false))))

        compose.onNodeWithText("Insufficient balance for API calls").assertIsDisplayed()
    }

    @Test
    fun `a balance in error explains the failure`() {
        show(
            card(
                service = AiService.DEEPSEEK,
                balances = listOf(balance(error = AppError.AuthError(AiService.DEEPSEEK, isTerminal = true)))
            )
        )

        compose.onNodeWithText("Invalid API key", substring = true).assertIsDisplayed()
    }

    @Test
    fun `spending rows show each period`() {
        show(
            card(
                service = AiService.OPENAI,
                spending = listOf(
                    SpendRowUi(SpendPeriod.TODAY, 1.25, "USD"),
                    SpendRowUi(SpendPeriod.THIS_MONTH, 20.0, "USD")
                )
            )
        )

        compose.onNodeWithText("Spend").assertIsDisplayed()
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("1.25 USD").assertIsDisplayed()
        compose.onNodeWithText("This month").assertIsDisplayed()
    }

    @Test
    fun `an expandable card toggles on click`() {
        var toggles = 0
        show(card(windows = listOf(UsageWindowUi("5-Hour", 0.3))), onToggle = { toggles++ })

        compose.onNodeWithContentDescription("Expand").assertExists()
        compose.onNodeWithText("Claude").performClick()

        assertEquals(1, toggles)
        compose.onNodeWithText("Expanded details").assertDoesNotExist()
    }

    @Test
    fun `an expanded card shows its extra content`() {
        show(card(), onToggle = {}, expanded = true)

        compose.onNodeWithContentDescription("Collapse").assertExists()
        compose.onNodeWithText("Expanded details").assertIsDisplayed()
    }

    @Test
    fun `a non expandable card never shows extra content`() {
        show(card(), expanded = true)

        compose.onNodeWithText("Expanded details").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand").assertDoesNotExist()
    }
}
