package fr.gildasledrogoff.tokenusage.feature.diagnostics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.gildasledrogoff.tokenusage.core.diagnostics.AccountDiagnostic
import fr.gildasledrogoff.tokenusage.core.diagnostics.BackgroundSnapshot
import fr.gildasledrogoff.tokenusage.core.diagnostics.BackgroundStateSource
import fr.gildasledrogoff.tokenusage.core.diagnostics.ConfiguredServicesSource
import fr.gildasledrogoff.tokenusage.core.diagnostics.DiagnosticsMerger
import fr.gildasledrogoff.tokenusage.core.diagnostics.DiagnosticsReport
import fr.gildasledrogoff.tokenusage.core.diagnostics.DiagnosticsStore
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
data class DiagnosticsUiState(
    val loading: Boolean = true,
    val generatedAt: Instant = Instant.EPOCH,
    val accounts: List<AccountDiagnostic> = emptyList(),
    val background: BackgroundSnapshot? = null
)
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val lastFetchRepository: LastFetchRepository,
    private val store: DiagnosticsStore,
    private val backgroundState: BackgroundStateSource,
    private val configuredServices: ConfiguredServicesSource,
    private val clock: Clock
) : ViewModel() {
    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()
    init {
        refresh()
    }
    
    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val dashboard = try {
                lastFetchRepository.snapshot()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val background = try {
                backgroundState.read()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            _state.value = DiagnosticsUiState(
                loading = false,
                generatedAt = clock.instant(),
                accounts = DiagnosticsMerger.merge(store.records(), dashboard, configuredServices::isConfigured),
                background = background
            )
        }
    }
    
    fun buildReport(app: DiagnosticsReport.AppInfo): String {
        val current = _state.value
        return DiagnosticsReport.build(clock.instant(), app, current.accounts, current.background)
    }
}
