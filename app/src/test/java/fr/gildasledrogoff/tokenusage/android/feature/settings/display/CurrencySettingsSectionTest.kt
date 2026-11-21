package fr.gildasledrogoff.tokenusage.feature.settings.display

import android.app.Application
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.DisplayCurrency
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.ui.theme.TokenUsageTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    application = Application::class,
    qualifiers = "en-w411dp-h891dp"
)
class CurrencySettingsSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val defaultLocale = Locale.getDefault()
    private val events = mutableListOf<Any>()

    @Before
    fun setUp() = Locale.setDefault(Locale.US)

    @After
    fun tearDown() = Locale.setDefault(defaultLocale)

    private fun show(state: CurrencyConversionUi) = compose.setContent {
        TokenUsageTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
            CurrencySettingsSection(
                state = state,
                onDisplayCurrencyChange = { events += it },
                onSourceChange = { events += it },
                onApiFetchAllowedChange = { events += "api=$it" },
                onManualUsdEurChange = { events += "eur=$it" },
                onManualUsdCnyChange = { events += "cny=$it" },
                onRefreshRates = { events += "refresh" }
            )
        }
    }

    @Test
    fun `original currency hides the rate settings`() {
        show(CurrencyConversionUi())

        compose.onNodeWithText("Display currency").assertIsDisplayed()
        compose.onNodeWithText("Amounts stay in the currency", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Exchange rate").assertDoesNotExist()
    }

    @Test
    fun `picking a currency is reported`() {
        show(CurrencyConversionUi())

        compose.onNodeWithText("EUR").performClick()

        assertEquals(listOf<Any>(DisplayCurrency.EUR), events)
    }

    @Test
    fun `a converted currency lists the rate sources`() {
        show(CurrencyConversionUi(displayCurrency = DisplayCurrency.EUR, source = FxRateSource.ECB))

        compose.onNodeWithText("Exchange rate").assertIsDisplayed()
        compose.onNode(isSelectable() and hasText("ECB", substring = true)).assertIsSelected()

        compose.onNodeWithText("Frankfurter").performClick()

        assertEquals(listOf<Any>(FxRateSource.FRANKFURTER), events)
    }

    @Test
    fun `manual source shows both rate fields and reports edits`() {
        show(
            CurrencyConversionUi(
                displayCurrency = DisplayCurrency.EUR,
                source = FxRateSource.MANUAL,
                manualUsdEurText = "0.9",
                manualUsdCnyText = "x",
                manualUsdCnyInvalid = true
            )
        )

        compose.onNodeWithText("1 USD = … EUR").assertIsDisplayed()
        compose.onNodeWithText("Enter a positive number").assertIsDisplayed()

        compose.onNodeWithText("0.9").performTextReplacement("0.95")

        assertEquals(listOf<Any>("eur=0.95"), events)
    }

    @Test
    fun `remote source without consent explains that updates are off`() {
        show(CurrencyConversionUi(displayCurrency = DisplayCurrency.USD, apiFetchAllowed = false))

        compose.onNodeWithText("Rate updates are off", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Refresh").assertDoesNotExist()

        compose.onNodeWithText("Allow rate updates from the API").performClick()

        assertEquals(listOf<Any>("api=true"), events)
    }

    @Test
    fun `remote source with consent can refresh and shows the rate`() {
        show(
            CurrencyConversionUi(
                displayCurrency = DisplayCurrency.EUR,
                apiFetchAllowed = true,
                remoteRates = listOf(FxRate("USD", "EUR", 0.9234, FxRateSource.FRANKFURTER))
            )
        )

        compose.onNodeWithText("1 USD = 0.9234 EUR · Frankfurter").assertIsDisplayed()

        compose.onNodeWithText("Refresh").performClick()

        assertEquals(listOf<Any>("refresh"), events)
    }

    @Test
    fun `without any rate yet the user is told so`() {
        show(CurrencyConversionUi(displayCurrency = DisplayCurrency.EUR, apiFetchAllowed = true))

        compose.onNodeWithText("No rate fetched yet.").assertIsDisplayed()
    }

    @Test
    fun `a fetch error is shown with its reason`() {
        show(
            CurrencyConversionUi(
                displayCurrency = DisplayCurrency.EUR,
                apiFetchAllowed = true,
                fetchError = AppError.RateLimited
            )
        )

        compose.onNodeWithText("Couldn't fetch the rate: Rate limited").assertIsDisplayed()
    }

    @Test
    fun `fetching hides the refresh button`() {
        show(CurrencyConversionUi(displayCurrency = DisplayCurrency.EUR, apiFetchAllowed = true, isFetching = true))

        compose.onNodeWithText("Refresh").assertDoesNotExist()
    }
}
