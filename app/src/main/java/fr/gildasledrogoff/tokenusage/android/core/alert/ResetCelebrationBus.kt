package fr.gildasledrogoff.tokenusage.core.alert

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ResetCelebration(val service: AiService, val atMillis: Long)

@Singleton
class ResetCelebrationBus @Inject constructor() {
    private val _pending = MutableStateFlow<ResetCelebration?>(null)
    val pending: StateFlow<ResetCelebration?> = _pending.asStateFlow()

    fun trigger(service: AiService, atMillis: Long = System.currentTimeMillis()) {
        _pending.value = ResetCelebration(service, atMillis)
    }

    fun consume() {
        _pending.value = null
    }
}
