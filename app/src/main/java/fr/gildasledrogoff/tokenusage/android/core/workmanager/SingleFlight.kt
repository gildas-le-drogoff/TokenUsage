package fr.gildasledrogoff.tokenusage.core.workmanager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SingleFlight<T>(private val scope: CoroutineScope) {
    private class Flight<T>(val deferred: Deferred<T>) {
        var waiters = 0
    }

    private val mutex = Mutex()
    private var current: Flight<T>? = null
    suspend fun run(block: suspend () -> T): T {
        val flight = mutex.withLock {
            val running = current?.takeIf { it.deferred.isActive }
                ?: Flight(scope.async { block() }).also { current = it }
            running.also { it.waiters++ }
        }
        try {
            return flight.deferred.await()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    flight.waiters--
                    if (flight.waiters == 0 && flight.deferred.isActive) flight.deferred.cancel()
                }
            }
        }
    }
}
