package fr.gildasledrogoff.tokenusage.feature.settings.accounts

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Link between the account screen ([AccountsViewModel]) and the rest of the settings
 * (backup export/import, "delete everything"), which live in other view models.
 *
 * - [flushPending] / [discardPending]: persist or drop the credentials still being edited.
 * - [notifyExternalChange]: the stored accounts were replaced behind the account screen's back
 *   (restore, wipe); it reloads its state from the preferences.
 */
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

    /** Synchronous observers of [notifyExternalChange] (the flow above is the usual way to listen). */
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
