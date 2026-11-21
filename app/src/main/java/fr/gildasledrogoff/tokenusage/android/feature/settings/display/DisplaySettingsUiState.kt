package fr.gildasledrogoff.tokenusage.feature.settings.display
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.DisplayCurrency
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.LockScreenVisibility
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
data class DisplaySettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val quotaDisplayMode: QuotaDisplayMode = QuotaDisplayMode.REMAINING,
    val lockScreenVisibility: LockScreenVisibility = LockScreenVisibility.SYSTEM,
    val currencyConversion: CurrencyConversionUi = CurrencyConversionUi()
)
data class CurrencyConversionUi(
    val displayCurrency: DisplayCurrency = DisplayCurrency.ORIGINAL,
    val source: FxRateSource = FxRateSource.DEFAULT,
    val apiFetchAllowed: Boolean = false,
    val manualUsdEurText: String = "",
    val manualUsdEurInvalid: Boolean = false,
    val manualUsdCnyText: String = "",
    val manualUsdCnyInvalid: Boolean = false,
    val isFetching: Boolean = false,
    val remoteRates: List<FxRate> = emptyList(),
    val fetchError: AppError? = null
)
