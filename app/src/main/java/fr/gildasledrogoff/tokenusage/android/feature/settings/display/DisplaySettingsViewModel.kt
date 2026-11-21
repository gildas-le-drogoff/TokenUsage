package fr.gildasledrogoff.tokenusage.feature.settings.display

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.gildasledrogoff.tokenusage.core.domain.model.Currencies
import fr.gildasledrogoff.tokenusage.core.domain.model.DisplayCurrency
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.LockScreenVisibility
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import fr.gildasledrogoff.tokenusage.core.notification.QuotaNotificationService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.tile.QuotaTileService
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/** Theme, quota display mode, lock-screen visibility and currency conversion. */
@HiltViewModel
class DisplaySettingsViewModel @Inject constructor(
    private val prefsManager: EncryptedPrefsManager,
    private val exchangeRates: ExchangeRateRepository,
    private val displayPreferences: DisplayPreferencesRepository,
    private val widgetQuotaPublisher: WidgetQuotaPublisher,
    private val notificationService: QuotaNotificationService,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DisplaySettingsUiState(
            themeMode = displayPreferences.themeMode.value,
            quotaDisplayMode = displayPreferences.quotaDisplayMode.value,
            lockScreenVisibility = displayPreferences.lockScreenVisibility.value
        )
    )
    val uiState: StateFlow<DisplaySettingsUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(currencyConversion = loadCurrencyConversion()) }
    }

    private fun loadCurrencyConversion(): CurrencyConversionUi {
        val displayCurrency = prefsManager.getDisplayCurrency()
        val source = prefsManager.getFxRateSource()
        return CurrencyConversionUi(
            displayCurrency = displayCurrency,
            source = source,
            apiFetchAllowed = prefsManager.isFxApiFetchAllowed(),
            manualUsdEurText = prefsManager.getManualUsdEurRate()?.let(::formatRate) ?: "",
            manualUsdCnyText = prefsManager.getManualUsdCnyRate()?.let(::formatRate) ?: "",
            remoteRates = cachedRemoteRates(source, displayCurrency)
        )
    }

    private fun previewCurrencies(displayCurrency: DisplayCurrency): List<String> =
        if (displayCurrency.code == null) emptyList() else Currencies.PROVIDER.filter { it != displayCurrency.code }

    private fun cachedRemoteRates(source: FxRateSource, displayCurrency: DisplayCurrency): List<FxRate> {
        val target = displayCurrency.code ?: return emptyList()
        if (!source.isRemote) return emptyList()
        return previewCurrencies(displayCurrency).mapNotNull { exchangeRates.cachedRate(source, it, target) }
    }

    fun setDisplayCurrency(displayCurrency: DisplayCurrency) {
        prefsManager.setDisplayCurrency(displayCurrency)
        _uiState.update {
            it.copy(
                currencyConversion = it.currencyConversion.copy(
                    displayCurrency = displayCurrency,
                    remoteRates = cachedRemoteRates(it.currencyConversion.source, displayCurrency),
                    fetchError = null
                )
            )
        }
    }

    fun setFxRateSource(source: FxRateSource) {
        prefsManager.setFxRateSource(source)
        _uiState.update {
            it.copy(
                currencyConversion = it.currencyConversion.copy(
                    source = source,
                    remoteRates = cachedRemoteRates(source, it.currencyConversion.displayCurrency),
                    fetchError = null
                )
            )
        }
    }

    fun setFxApiFetchAllowed(allowed: Boolean) {
        prefsManager.setFxApiFetchAllowed(allowed)
        _uiState.update {
            it.copy(currencyConversion = it.currencyConversion.copy(apiFetchAllowed = allowed, fetchError = null))
        }
        if (allowed) refreshFxRates()
    }

    fun updateManualUsdEurRate(text: String) {
        val (parsed, invalid) = parseManualRate(text)
        if (!invalid) prefsManager.setManualUsdEurRate(parsed)
        _uiState.update {
            it.copy(
                currencyConversion = it.currencyConversion.copy(manualUsdEurText = text, manualUsdEurInvalid = invalid)
            )
        }
    }

    fun updateManualUsdCnyRate(text: String) {
        val (parsed, invalid) = parseManualRate(text)
        if (!invalid) prefsManager.setManualUsdCnyRate(parsed)
        _uiState.update {
            it.copy(
                currencyConversion = it.currencyConversion.copy(manualUsdCnyText = text, manualUsdCnyInvalid = invalid)
            )
        }
    }

    private fun parseManualRate(text: String): Pair<Double?, Boolean> {
        val trimmed = text.trim()
        val parsed = parseRate(trimmed)
        return parsed to (trimmed.isNotEmpty() && parsed == null)
    }

    fun refreshFxRates() {
        val requested = _uiState.value.currencyConversion
        val target = requested.displayCurrency.code ?: return
        if (!requested.source.isRemote || !requested.apiFetchAllowed || requested.isFetching) return
        _uiState.update {
            it.copy(currencyConversion = it.currencyConversion.copy(isFetching = true, fetchError = null))
        }
        viewModelScope.launch {
            val results = previewCurrencies(requested.displayCurrency).map { from ->
                exchangeRates.fetchLatest(requested.source, from, target)
            }
            val rates = results.mapNotNull { (it as? Result.Success)?.value }
            val error = results.firstNotNullOfOrNull { (it as? Result.Failure)?.error }
            val stale = _uiState.value.currencyConversion.let {
                it.source != requested.source || it.displayCurrency != requested.displayCurrency
            }
            _uiState.update { state ->
                val current = state.currencyConversion.copy(isFetching = false)
                state.copy(
                    currencyConversion = when {
                        stale -> current
                        error != null -> current.copy(
                            remoteRates = rates.ifEmpty { current.remoteRates },
                            fetchError = error
                        )
                        else -> current.copy(remoteRates = rates, fetchError = null)
                    }
                )
            }
            
            if (stale) refreshFxRates()
        }
    }

    private fun parseRate(text: String): Double? =
        text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 && it < MAX_MANUAL_RATE }

    private fun formatRate(rate: Double): String =
        BigDecimal(rate.toString()).stripTrailingZeros().toPlainString()

    fun setThemeMode(mode: ThemeMode) {
        displayPreferences.setThemeMode(mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setQuotaDisplayMode(mode: QuotaDisplayMode) {
        displayPreferences.setQuotaDisplayMode(mode)
        _uiState.update { it.copy(quotaDisplayMode = mode) }
        viewModelScope.launch { runCatching { widgetQuotaPublisher.refreshWidgets() } }
    }

    fun setLockScreenVisibility(visibility: LockScreenVisibility) {
        displayPreferences.setLockScreenVisibility(visibility)
        _uiState.update { it.copy(lockScreenVisibility = visibility) }
        runCatching { notificationService.reapplyLockScreenVisibility() }
        runCatching {
            TileService.requestListeningState(appContext, ComponentName(appContext, QuotaTileService::class.java))
        }
    }

    private companion object {

        const val MAX_MANUAL_RATE = 1000.0
    }
}
