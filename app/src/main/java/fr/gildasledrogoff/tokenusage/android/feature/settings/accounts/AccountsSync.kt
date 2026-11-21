package fr.gildasledrogoff.tokenusage.feature.settings.accounts
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountsSync @Inject constructor() {
    interface PendingEdits {
        fun flush()
        fun discard()
    }
    private val _externalChanges = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val externalChanges: SharedFlow<Unit> = _externalChanges.asSharedFlow()
    
    val listeners: MutableList<() -> Unit> = CopyOnWriteArrayList()
    @Volatile
    private var pendingEdits: PendingEdits? = null
    fun register(edits: PendingEdits) {
        pendingEdits = edits
    }
    fun unregister(edits: PendingEdits) {
        if (pendingEdits === edits) pendingEdits = null
    }
    fun flushPending() {
        pendingEdits?.flush()
    }
    fun discardPending() {
        pendingEdits?.discard()
    }
    fun notifyExternalChange() {
        _externalChanges.tryEmit(Unit)
        listeners.forEach { it() }
    }
}
