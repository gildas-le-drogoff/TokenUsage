package fr.gildasledrogoff.tokenusage.feature.settings.display
import android.content.Context
import app.cash.turbine.test
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.DisplayCurrency
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.LockScreenVisibility
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import fr.gildasledrogoff.tokenusage.core.notification.QuotaNotificationService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.security.InMemorySharedPreferences
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import fr.gildasledrogoff.tokenusage.feature.settings.FakeDisplayPreferences
import fr.gildasledrogoff.tokenusage.feature.settings.inMemoryPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
@OptIn(ExperimentalCoroutinesApi::class)
class DisplaySettingsViewModelTest {
    private class FakeExchangeRates : ExchangeRateRepository {
        val cached = mutableMapOf<Pair<String, String>, FxRate>()
        var fetch: (from: String, to: String) -> Result<FxRate, AppError> = { from, to ->
            Result.Success(FxRate(from, to, 2.0, FxRateSource.FRANKFURTER, "2026-11-21"))
        }
        val fetched = mutableListOf<Pair<String, String>>()
        override suspend fun displayRate(currency: String): FxRate? = null
        override suspend fun fetchLatest(source: FxRateSource, from: String, to: String): Result<FxRate, AppError> {
            fetched += from to to
            return fetch(from, to)
        }
        override fun cachedRate(source: FxRateSource, from: String, to: String): FxRate? = cached[from to to]
    }
    private val store = InMemorySharedPreferences()
    private lateinit var prefs: EncryptedPrefsManager
    private val displayPreferences = FakeDisplayPreferences()
    private val rates = FakeExchangeRates()
    private val widgets = mock(WidgetQuotaPublisher::class.java)
    private val notifications = mock(QuotaNotificationService::class.java)
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        prefs = inMemoryPrefsManager(store)
    }
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }
    private fun viewModel() = DisplaySettingsViewModel(
        prefsManager = prefs,
        exchangeRates = rates,
        displayPreferences = displayPreferences,
        widgetQuotaPublisher = widgets,
        notificationService = notifications,
        appContext = mock(Context::class.java)
    )
    @Test
    fun `initial state reflects the saved preferences`() {
        displayPreferences.themeMode.value = ThemeMode.DARK
        displayPreferences.quotaDisplayMode.value = QuotaDisplayMode.USED
        displayPreferences.lockScreenVisibility.value = LockScreenVisibility.HIDDEN
        prefs.setDisplayCurrency(DisplayCurrency.EUR)
        prefs.setFxRateSource(FxRateSource.ECB)
        prefs.setFxApiFetchAllowed(true)
        prefs.setManualUsdEurRate(0.92)
        val state = viewModel().uiState.value
        assertEquals(ThemeMode.DARK, state.themeMode)
        assertEquals(QuotaDisplayMode.USED, state.quotaDisplayMode)
        assertEquals(LockScreenVisibility.HIDDEN, state.lockScreenVisibility)
        assertEquals(DisplayCurrency.EUR, state.currencyConversion.displayCurrency)
        assertEquals(FxRateSource.ECB, state.currencyConversion.source)
        assertTrue(state.currencyConversion.apiFetchAllowed)
        assertEquals("0.92", state.currencyConversion.manualUsdEurText)
    }
    @Test
    fun `theme change is saved and published`() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            assertEquals(ThemeMode.SYSTEM, awaitItem().themeMode)
            viewModel.setThemeMode(ThemeMode.LIGHT)
            assertEquals(ThemeMode.LIGHT, awaitItem().themeMode)
        }
        assertEquals(ThemeMode.LIGHT, displayPreferences.themeMode.value)
    }
    @Test
    fun `quota display mode change is saved and widgets are refreshed`() = runTest {
        val viewModel = viewModel()
        viewModel.setQuotaDisplayMode(QuotaDisplayMode.USED)
        assertEquals(QuotaDisplayMode.USED, viewModel.uiState.value.quotaDisplayMode)
        assertEquals(QuotaDisplayMode.USED, displayPreferences.quotaDisplayMode.value)
        verify(widgets).refreshWidgets()
    }
    @Test
    fun `lock screen visibility change is saved and notifications are updated`() {
        val viewModel = viewModel()
        viewModel.setLockScreenVisibility(LockScreenVisibility.SHOW_ALL)
        assertEquals(LockScreenVisibility.SHOW_ALL, viewModel.uiState.value.lockScreenVisibility)
        assertEquals(LockScreenVisibility.SHOW_ALL, displayPreferences.lockScreenVisibility.value)
        verify(notifications).reapplyLockScreenVisibility()
    }
    @Test
    fun `display currency change is saved and shows the cached rates`() {
        rates.cached["USD" to "EUR"] = FxRate("USD", "EUR", 0.9, FxRateSource.FRANKFURTER)
        val viewModel = viewModel()
        viewModel.setDisplayCurrency(DisplayCurrency.EUR)
        assertEquals(DisplayCurrency.EUR, prefs.getDisplayCurrency())
        val conversion = viewModel.uiState.value.currencyConversion
        assertEquals(DisplayCurrency.EUR, conversion.displayCurrency)
        assertEquals(listOf(FxRate("USD", "EUR", 0.9, FxRateSource.FRANKFURTER)), conversion.remoteRates)
        assertNull(conversion.fetchError)
    }
    @Test
    fun `manual rate accepts a comma and is saved`() {
        val viewModel = viewModel()
        viewModel.updateManualUsdEurRate("0,92")
        assertEquals(0.92, prefs.getManualUsdEurRate()!!, 1e-9)
        val conversion = viewModel.uiState.value.currencyConversion
        assertEquals("0,92", conversion.manualUsdEurText)
        assertFalse(conversion.manualUsdEurInvalid)
    }
    @Test
    fun `invalid manual rates are flagged and not saved`() {
        val viewModel = viewModel()
        viewModel.updateManualUsdCnyRate("7.2")
        for (bad in listOf("abc", "0", "-3", "1000", "NaN")) {
            viewModel.updateManualUsdCnyRate(bad)
            assertTrue("'$bad' should be invalid", viewModel.uiState.value.currencyConversion.manualUsdCnyInvalid)
            assertEquals(7.2, prefs.getManualUsdCnyRate()!!, 1e-9)
        }
    }
    @Test
    fun `an emptied manual rate is valid and clears the saved one`() {
        val viewModel = viewModel()
        viewModel.updateManualUsdEurRate("0.9")
        viewModel.updateManualUsdEurRate("")
        assertFalse(viewModel.uiState.value.currencyConversion.manualUsdEurInvalid)
        assertNull(prefs.getManualUsdEurRate())
    }
    @Test
    fun `allowing the api fetch downloads the rates of the display currency`() {
        prefs.setDisplayCurrency(DisplayCurrency.EUR)
        val viewModel = viewModel()
        viewModel.setFxApiFetchAllowed(true)
        assertTrue(prefs.isFxApiFetchAllowed())
        val conversion = viewModel.uiState.value.currencyConversion
        assertEquals(setOf("USD" to "EUR", "CNY" to "EUR"), rates.fetched.toSet())
        assertEquals(2, conversion.remoteRates.size)
        assertFalse(conversion.isFetching)
        assertNull(conversion.fetchError)
    }
    @Test
    fun `no download without a display currency, a remote source or the permission`() {
        val original = viewModel()
        original.setFxApiFetchAllowed(true)
        assertTrue(rates.fetched.isEmpty())
        prefs.setDisplayCurrency(DisplayCurrency.EUR)
        val manual = viewModel()
        manual.setFxRateSource(FxRateSource.MANUAL)
        manual.setFxApiFetchAllowed(true)
        assertTrue(rates.fetched.isEmpty())
        val notAllowed = viewModel()
        notAllowed.setFxRateSource(FxRateSource.ECB)
        notAllowed.setFxApiFetchAllowed(false)
        notAllowed.refreshFxRates()
        assertTrue(rates.fetched.isEmpty())
    }
    @Test
    fun `a failed download keeps the previous rates and reports the error`() {
        prefs.setDisplayCurrency(DisplayCurrency.EUR)
        prefs.setFxApiFetchAllowed(true)
        rates.cached["USD" to "EUR"] = FxRate("USD", "EUR", 0.9, FxRateSource.FRANKFURTER)
        rates.fetch = { _, _ -> Result.Failure(AppError.NetworkError("offline")) }
        val viewModel = viewModel()
        viewModel.refreshFxRates()
        val conversion = viewModel.uiState.value.currencyConversion
        assertTrue(conversion.fetchError is AppError.NetworkError)
        assertEquals(1, conversion.remoteRates.size)
        assertFalse(conversion.isFetching)
    }
    @Test
    fun `changing the source clears a previous error`() {
        prefs.setDisplayCurrency(DisplayCurrency.EUR)
        prefs.setFxApiFetchAllowed(true)
        rates.fetch = { _, _ -> Result.Failure(AppError.NetworkError("offline")) }
        val viewModel = viewModel()
        viewModel.refreshFxRates()
        assertTrue(viewModel.uiState.value.currencyConversion.fetchError != null)
        rates.fetch = { from, to -> Result.Success(FxRate(from, to, 1.1, FxRateSource.ECB)) }
        viewModel.setFxRateSource(FxRateSource.ECB)
        assertEquals(FxRateSource.ECB, prefs.getFxRateSource())
        assertNull(viewModel.uiState.value.currencyConversion.fetchError)
    }
}
