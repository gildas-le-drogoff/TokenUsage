package fr.gildasledrogoff.tokenusage.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.gildasledrogoff.tokenusage.core.alert.BalanceSeriesSource
import fr.gildasledrogoff.tokenusage.core.alert.TimedValue
import fr.gildasledrogoff.tokenusage.core.data.WINDOW_5H
import fr.gildasledrogoff.tokenusage.core.data.WINDOW_7D
import fr.gildasledrogoff.tokenusage.core.data.claudeUtilizationKey
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

data class ClaudeCurves(
    val from: Instant,
    val to: Instant,
    val fiveHour: List<TimedValue>,
    val sevenDay: List<TimedValue>
) {
    val isEmpty: Boolean get() = fiveHour.size < 2 && sevenDay.size < 2
}

@HiltViewModel
class ClaudeCurvesViewModel @Inject constructor(
    private val series: BalanceSeriesSource,
    private val demoData: DemoDataRepository,
    private val clock: Clock
) : ViewModel() {
    private val _curves = MutableStateFlow<ClaudeCurves?>(null)
    val curves: StateFlow<ClaudeCurves?> = _curves.asStateFlow()

    fun load(accountId: String) {
        viewModelScope.launch {
            val to = clock.instant()
            val from = to.minus(Duration.ofDays(WINDOW_DAYS))
            _curves.value = try {
                if (demoData.isEnabled()) {
                    ClaudeCurves(
                        from = from,
                        to = to,
                        fiveHour = demoData.sampleUtilizationSeries(accountId, WINDOW_5H, from, to),
                        sevenDay = demoData.sampleUtilizationSeries(accountId, WINDOW_7D, from, to)
                    )
                } else {
                    ClaudeCurves(
                        from = from,
                        to = to,
                        fiveHour = series.series(
                            AiService.CLAUDE, claudeUtilizationKey(accountId, WINDOW_5H), from
                        ),
                        sevenDay = series.series(
                            AiService.CLAUDE, claudeUtilizationKey(accountId, WINDOW_7D), from
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ClaudeCurves(from, to, emptyList(), emptyList())
            }
        }
    }

    private companion object {
        const val WINDOW_DAYS = 7L
    }
}
