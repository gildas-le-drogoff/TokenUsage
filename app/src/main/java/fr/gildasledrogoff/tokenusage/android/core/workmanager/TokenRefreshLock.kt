package fr.gildasledrogoff.tokenusage.core.workmanager

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object TokenRefreshLock {
    private val mutex = Mutex()
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    val isLocked: Boolean get() = mutex.isLocked
}
