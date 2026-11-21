package fr.gildasledrogoff.tokenusage.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.gildasledrogoff.tokenusage.core.alert.AlertPreferencesStore
import fr.gildasledrogoff.tokenusage.core.alert.BalanceSeriesSource
import fr.gildasledrogoff.tokenusage.core.alert.DepletionForecast
import fr.gildasledrogoff.tokenusage.core.alert.DepletionPredictor
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import javax.inject.Inject

@HiltViewModel
class BalanceHistoryViewModel @Inject constructor(
    private val historyRepository: BalanceHistoryRepository,
    private val prefsManager: EncryptedPrefsManager,
    private val demoData: DemoDataRepository,
    private val exchangeRates: ExchangeRateRepository,
    private val balanceSeries: BalanceSeriesSource,
    private val depletionPredictor: DepletionPredictor,
    private val alertPreferences: AlertPreferencesStore,
    private val clock: Clock
) : ViewModel() {

    private val _uiState = MutableStateFlow(BalanceHistoryUiState())
    val uiState: StateFlow<BalanceHistoryUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun selectAccount(accountId: String) {
        if (_uiState.value.selectedAccountId == accountId) return
        _uiState.update { it.copy(selectedAccountId = accountId, isLoading = true) }
        load()
    }

    fun selectGranularity(granularity: ConsumptionGranularity) {
        if (_uiState.value.granularity == granularity) return
        _uiState.update { it.copy(granularity = granularity, isLoading = true) }
        load()
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            
            val demo = demoData.isEnabled()
            val accounts = if (demo) demoData.sampleDeepseekAccounts() else prefsManager.getDeepseekAccounts()
            val history = if (demo) demoData.history else historyRepository

            if (accounts.isEmpty()) {
                _uiState.value = BalanceHistoryUiState(isLoading = false)
                return@launch
            }

            val currentState = _uiState.value
            val selectedId = currentState.selectedAccountId
                ?.takeIf { id -> accounts.any { it.id == id } }
                ?: accounts.first().id

            val points = history.consumption(
                service = AiService.DEEPSEEK,
                accountId = selectedId,
                granularity = currentState.granularity
            )
            val latest = history.latestSample(AiService.DEEPSEEK, selectedId)
            val currency = latest?.currency ?: points.lastOrNull()?.currency ?: ""

            val depletion = if (demo) null else estimateDepletion(selectedId)

            val rate = exchangeRates.displayRate(currency)
            val shownPoints = if (rate == null) points else points.map { point ->
                if (point.currency == currency) {
                    point.copy(consumed = rate.convert(point.consumed), currency = rate.to)
                } else {
                    point
                }
            }

            _uiState.value = BalanceHistoryUiState(
                accounts = accounts,
                selectedAccountId = selectedId,
                granularity = currentState.granularity,
                points = shownPoints,
                latestBalance = latest?.totalBalance?.let { rate?.convert(it) ?: it },
                currency = rate?.to ?: currency,
                fxRate = rate,
                depletionAt = depletion?.depletionAt,
                depletionEstimated = !demo,
                isLoading = false
            )
        }
    }

    /** Date the balance is expected to reach zero, from the recorded history (`null` if it cannot be told). */
    private suspend fun estimateDepletion(accountId: String): DepletionForecast? {
        val lookback = Duration.ofDays(alertPreferences.currentConfig().forecastWindowDays.toLong())
        return try {
            val points = balanceSeries.series(AiService.DEEPSEEK, accountId, clock.instant().minus(lookback))
            depletionPredictor.predictBalance(points, target = 0.0, lookback = lookback)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
