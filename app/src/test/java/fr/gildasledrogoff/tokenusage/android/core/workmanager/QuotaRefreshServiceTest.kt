package fr.gildasledrogoff.tokenusage.core.workmanager

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.core.alert.QuotaEventDispatcher
import fr.gildasledrogoff.tokenusage.core.diagnostics.DiagnosticsStore
import fr.gildasledrogoff.tokenusage.core.diagnostics.RefreshOrigin
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoQuota
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import fr.gildasledrogoff.tokenusage.core.notification.QuotaNotificationService
import fr.gildasledrogoff.tokenusage.core.provider.CredentialForm
import fr.gildasledrogoff.tokenusage.core.provider.ProviderRegistry
import fr.gildasledrogoff.tokenusage.core.provider.QuotaProvider
import fr.gildasledrogoff.tokenusage.core.provider.apiKeyForm
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.security.InMemorySharedPreferences
import fr.gildasledrogoff.tokenusage.core.widget.WidgetDataStatus
import fr.gildasledrogoff.tokenusage.core.widget.WidgetPrefsManager
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import fr.gildasledrogoff.tokenusage.feature.settings.FakeDisplayPreferences
import fr.gildasledrogoff.tokenusage.feature.settings.inMemoryPrefsManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    application = Application::class,
    qualifiers = "en"
)
class QuotaRefreshServiceTest {
    private class FakeProvider(override val service: AiService) : QuotaProvider {
        override val form: CredentialForm =
            apiKeyForm(labelRes = 1, fieldHintRes = 2) { Credential.ApiKeyCredential(it) }
        val accounts = mutableListOf<ServiceAccount>()
        val results = mutableMapOf<String, Result<QuotaInfo, AppError>>()
        override fun accounts(): List<ServiceAccount> = accounts.toList()
        override suspend fun fetchQuota(account: ServiceAccount): Result<QuotaInfo, AppError> =
            results[account.id] ?: Result.Success(quota(service, account.id, 0.3))
    }

    private val noRates = object : ExchangeRateRepository {
        override suspend fun displayRate(currency: String) = null
        override suspend fun fetchLatest(
            source: fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource,
            from: String,
            to: String
        ): Result<fr.gildasledrogoff.tokenusage.core.domain.model.FxRate, AppError> = error("unused")

        override fun cachedRate(
            source: fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource,
            from: String,
            to: String
        ) = null
    }

    private lateinit var context: Context
    private lateinit var providers: Map<AiService, FakeProvider>
    private lateinit var prefsManager: EncryptedPrefsManager
    private lateinit var widgetPrefs: WidgetPrefsManager
    private lateinit var diagnostics: DiagnosticsStore
    private lateinit var reconnect: FakeReconnectRefresh
    private val demo = mock(DemoDataRepository::class.java)
    private val events = mock(QuotaEventDispatcher::class.java)
    private var online = true

    private val notifications
        get() = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .allNotifications

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        providers = AiService.entries.associateWith { FakeProvider(it) }
        prefsManager = inMemoryPrefsManager()
        widgetPrefs = WidgetPrefsManager(context)
        diagnostics = DiagnosticsStore(lazy { InMemorySharedPreferences() }, Clock.systemUTC())
        reconnect = FakeReconnectRefresh()
    }

    private fun service() = QuotaRefreshService(
        context = context,
        providerRegistry = ProviderRegistry(providers.values.toSet()),
        prefsManager = prefsManager,
        demoData = demo,
        notificationService = QuotaNotificationService(context, FakeDisplayPreferences()),
        quotaEvents = events,
        widgetQuotaPublisher = WidgetQuotaPublisher(context, widgetPrefs, noRates),
        diagnosticsStore = diagnostics,
        networkMonitor = { online },
        reconnectRefresh = reconnect
    )

    private fun addAccount(service: AiService, id: String, label: String = "") {
        providers.getValue(service).accounts += ServiceAccount(id, label, Credential.ApiKeyCredential("k"))
    }

    @Test
    fun `nothing configured reports no service`() = runTest {
        val outcome = service().refreshNow()

        assertEquals(QuotaRefreshService.Outcome(emptyList(), anyServiceConfigured = false), outcome)
        assertTrue(diagnostics.records().isEmpty())
    }

    @Test
    fun `a successful refresh notifies, publishes and records diagnostics`() = runTest {
        addAccount(AiService.CLAUDE, "c1")
        addAccount(AiService.CODEX, "x1")

        val outcome = service().refreshNow(RefreshOrigin.PERIODIC_WORKER)

        assertEquals(setOf(AiService.CLAUDE, AiService.CODEX), outcome.quotas.map { it.service }.toSet())
        assertTrue(outcome.anyServiceConfigured)
        assertFalse(outcome.offline)
        assertEquals(1, notifications.count { it.channelId == QuotaNotificationService.CHANNEL_ID })
        assertEquals(1, widgetPrefs.getCachedAccounts(AiService.CLAUDE).size)
        assertEquals(2, diagnostics.records().size)
        assertTrue(diagnostics.records().all { it.origin == RefreshOrigin.PERIODIC_WORKER })
        assertEquals(1, reconnect.onlineSignals)
        verify(events).dispatch(outcome.quotas, false)
    }

    @Test
    fun `disabled notifications skip the ongoing notification`() = runTest {
        prefsManager.setNotificationsEnabled(false)
        addAccount(AiService.CLAUDE, "c1")

        service().refreshNow()

        assertTrue(notifications.isEmpty())
    }

    @Test
    fun `failures are kept out of the quotas but still cached as failed`() = runTest {
        addAccount(AiService.CLAUDE, "c1")
        addAccount(AiService.CODEX, "x1")
        providers.getValue(AiService.CODEX).results["x1"] = Result.Failure(AppError.RateLimited)

        val outcome = service().refreshNow()

        assertEquals(listOf(AiService.CLAUDE), outcome.quotas.map { it.service })
        assertEquals(WidgetDataStatus.FAILED, widgetPrefs.getCachedAccounts(AiService.CODEX).single().status)
    }

    @Test
    fun `all connectivity failures mean offline`() = runTest {
        addAccount(AiService.CLAUDE, "c1")
        providers.getValue(AiService.CLAUDE).results["c1"] =
            Result.Failure(AppError.NetworkError("dns", UnknownHostException("api")))

        val outcome = service().refreshNow()

        assertTrue(outcome.offline)
        assertTrue(outcome.quotas.isEmpty())
        assertEquals(1, reconnect.offlineSignals)
        verify(events, never()).dispatch(anyList(), anyBoolean())
    }

    @Test
    fun `no network skips fetching and marks widgets offline`() = runTest {
        online = false
        addAccount(AiService.CLAUDE, "c1", "Work")

        val outcome = service().refreshNow()

        assertEquals(QuotaRefreshService.Outcome(emptyList(), anyServiceConfigured = true, offline = true), outcome)
        assertEquals(WidgetDataStatus.OFFLINE, widgetPrefs.getCachedAccounts(AiService.CLAUDE).single().status)
        assertEquals(1, reconnect.offlineSignals)
        assertTrue(diagnostics.records().isEmpty())
    }

    @Test
    fun `a reconnect refresh does not signal the reconnect scheduler`() = runTest {
        online = false
        addAccount(AiService.CLAUDE, "c1")

        service().refreshNow(RefreshOrigin.RECONNECT)
        online = true
        service().refreshNow(RefreshOrigin.RECONNECT)

        assertEquals(0, reconnect.offlineSignals)
        assertEquals(0, reconnect.onlineSignals)
    }

    @Test
    fun `a window that reset since last refresh posts a reset notification`() = runTest {
        addAccount(AiService.CLAUDE, "c1")
        prefsManager.saveResetTimes(AiService.CLAUDE, listOf("5-Hour" to Instant.now().minusSeconds(60)))
        providers.getValue(AiService.CLAUDE).results["c1"] = Result.Success(
            QuotaInfo(
                AiService.CLAUDE,
                listOf(UsageWindow("5-Hour", 0.0, Instant.now().plusSeconds(5 * 3600))),
                accountId = "c1"
            )
        )

        service().refreshNow()

        assertEquals(1, notifications.count { it.channelId == QuotaNotificationService.RESET_CHANNEL_ID })
        assertTrue(prefsManager.loadResetTimes(AiService.CLAUDE).getValue("5-Hour").isAfter(Instant.now()))
    }

    @Test
    fun `demo mode publishes sample quotas without fetching`() = runTest {
        addAccount(AiService.CLAUDE, "c1")
        `when`(demo.isEnabled()).thenReturn(true)
        `when`(demo.sampleQuotas()).thenReturn(
            listOf(
                DemoQuota(AiService.ZAI, "Demo", Result.Success(quota(AiService.ZAI, "d", 0.5))),
                DemoQuota(AiService.CODEX, null, Result.Failure(AppError.RateLimited))
            )
        )

        val outcome = service().refreshNow()

        assertEquals(listOf(AiService.ZAI), outcome.quotas.map { it.service })
        assertTrue(outcome.anyServiceConfigured)
        assertEquals("Demo", widgetPrefs.getCachedAccounts(AiService.ZAI).single().accountLabel)
        assertTrue(widgetPrefs.getCachedAccounts(AiService.CLAUDE).isEmpty())
    }

    @Test
    fun `demo mode without any sample publishes nothing`() = runTest {
        `when`(demo.isEnabled()).thenReturn(true)
        `when`(demo.sampleQuotas()).thenReturn(emptyList())

        val outcome = service().refreshNow()

        assertTrue(outcome.quotas.isEmpty())
        assertTrue(outcome.anyServiceConfigured)
    }

    private companion object {
        fun quota(service: AiService, accountId: String, used: Double) = QuotaInfo(
            service,
            listOf(UsageWindow("5-Hour", used, null)),
            accountId = accountId
        )
    }
}
