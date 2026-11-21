package fr.gildasledrogoff.tokenusage.feature.dashboard

import android.app.Application
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.gildasledrogoff.tokenusage.core.alert.BalanceSeriesSource
import fr.gildasledrogoff.tokenusage.core.alert.QuotaEventDispatcher
import fr.gildasledrogoff.tokenusage.core.alert.ResetCelebrationBus
import fr.gildasledrogoff.tokenusage.core.data.FetchRateLimiter
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.CachedQuotaResult
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchSnapshot
import fr.gildasledrogoff.tokenusage.core.network.NetworkMonitor
import fr.gildasledrogoff.tokenusage.core.provider.CredentialForm
import fr.gildasledrogoff.tokenusage.core.provider.ProviderRegistry
import fr.gildasledrogoff.tokenusage.core.provider.QuotaProvider
import fr.gildasledrogoff.tokenusage.core.provider.apiKeyForm
import fr.gildasledrogoff.tokenusage.core.status.ProviderStatusRepository
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import fr.gildasledrogoff.tokenusage.core.workmanager.FakeReconnectRefresh
import fr.gildasledrogoff.tokenusage.feature.settings.FakeDisplayPreferences
import fr.gildasledrogoff.tokenusage.feature.settings.inMemoryPrefsManager
import fr.gildasledrogoff.tokenusage.ui.theme.TokenUsageTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    application = Application::class,
    qualifiers = "en-w411dp-h891dp"
)
class DashboardScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private class FakeProvider(override val service: AiService) : QuotaProvider {
        override val form: CredentialForm =
            apiKeyForm(labelRes = 1, fieldHintRes = 2) { Credential.ApiKeyCredential(it) }
        val accounts = mutableListOf<ServiceAccount>()
        val results = mutableMapOf<String, Result<QuotaInfo, AppError>>()
        override fun accounts(): List<ServiceAccount> = accounts.toList()
        override suspend fun fetchQuota(account: ServiceAccount): Result<QuotaInfo, AppError> =
            results[account.id] ?: Result.Success(
                QuotaInfo(service, listOf(UsageWindow("5-Hour", 0.3, null)), accountId = account.id)
            )
    }

    private class FakeLastFetch : LastFetchRepository {
        var stored: LastFetchSnapshot? = null
        override suspend fun snapshot(): LastFetchSnapshot? = stored
        override suspend fun record(fetchedAt: Instant, results: List<CachedQuotaResult>) {
            stored = LastFetchSnapshot(fetchedAt, results)
        }
    }

    private val noRates = object : ExchangeRateRepository {
        override suspend fun displayRate(currency: String): FxRate? = null
        override suspend fun fetchLatest(source: FxRateSource, from: String, to: String): Result<FxRate, AppError> =
            Result.Failure(AppError.NetworkError("offline"))

        override fun cachedRate(source: FxRateSource, from: String, to: String): FxRate? = null
    }

    private val now = Instant.parse("2026-11-21T10:00:00Z")
    private lateinit var providers: Map<AiService, FakeProvider>
    private lateinit var display: FakeDisplayPreferences
    private val statusRepository = mock(ProviderStatusRepository::class.java)
    private val demo = mock(DemoDataRepository::class.java)
    private var online = true

    @Before
    fun setUp() {
        providers = AiService.entries.associateWith { FakeProvider(it) }
        display = FakeDisplayPreferences()
        `when`(statusRepository.statuses).thenReturn(MutableStateFlow(emptyMap()))
    }

    private fun viewModel() = DashboardViewModel(
        providerRegistry = ProviderRegistry(providers.values.toSet()),
        prefsManager = inMemoryPrefsManager(),
        demoData = demo,
        exchangeRates = noRates,
        widgetQuotaPublisher = mock(WidgetQuotaPublisher::class.java),
        balanceHistory = mock(BalanceHistoryRepository::class.java),
        displayPreferences = display,
        lastFetchRepository = FakeLastFetch(),
        rateLimiter = FetchRateLimiter(Clock.fixed(now, ZoneOffset.UTC)),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        statusRepository = statusRepository,
        celebrationBus = ResetCelebrationBus(),
        balanceSeries = mock(BalanceSeriesSource::class.java),
        quotaEvents = mock(QuotaEventDispatcher::class.java),
        networkMonitor = object : NetworkMonitor {
            override fun isOnline(): Boolean = online
            override fun onlineChanges(): Flow<Boolean> = emptyFlow()
        },
        reconnectRefresh = FakeReconnectRefresh()
    )

    private fun addAccount(service: AiService, id: String, label: String = "") {
        providers.getValue(service).accounts += ServiceAccount(id, label, Credential.ApiKeyCredential("k"))
    }

    private var settingsOpened = 0

    private fun show(vm: DashboardViewModel = viewModel()) = compose.setContent {
        TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
            DashboardScreen(onNavigateToSettings = { settingsOpened++ }, viewModel = vm)
        }
    }

    @Test
    fun `no account shows the empty state`() {
        show()

        waitForText(context().getString(fr.gildasledrogoff.tokenusage.R.string.dashboard_empty_title))
    }

    @Test
    fun `configured accounts are shown as cards with the last update`() {
        addAccount(AiService.CODEX, "x1", "Work")

        show()

        waitForText("Codex")
        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("Last updated:", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("5-Hour: 70% left, OK").assertExists()
    }

    @Test
    fun `a failing service is listed in the error banner`() {
        addAccount(AiService.CODEX, "x1")
        addAccount(AiService.ZAI, "z1")
        providers.getValue(AiService.ZAI).results["z1"] = Result.Failure(AppError.RateLimited)

        show()

        waitForText("Failed to load: Z.ai", substring = true)
    }

    @Test
    fun `every service failing without data shows the load error`() {
        addAccount(AiService.CODEX, "x1")
        providers.getValue(AiService.CODEX).results["x1"] = Result.Failure(AppError.RateLimited)

        show()

        compose.waitUntil(5_000) { anyText("Failed to load quota data") || anyText("Rate limited") }
    }

    @Test
    fun `settings button navigates`() {
        show()

        compose.onNodeWithContentDescription("Settings").performClick()

        assertEquals(1, settingsOpened)
    }

    @Test
    fun `theme button switches to dark from a light theme`() {
        show()

        compose.onNodeWithContentDescription("Toggle light/dark theme").performClick()

        assertEquals(ThemeMode.DARK, display.themeMode.value)
    }

    private fun context() = androidx.test.core.app.ApplicationProvider
        .getApplicationContext<android.content.Context>()

    private fun anyText(text: String, substring: Boolean = false) =
        compose.onAllNodes(androidx.compose.ui.test.hasText(text, substring = substring))
            .fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(5_000) { anyText(text, substring) }
        compose.onNodeWithText(text, substring = substring).assertIsDisplayed()
    }
}
