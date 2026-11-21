package fr.gildasledrogoff.tokenusage.feature.settings.accounts

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountsSyncTest {
    private class Edits : AccountsSync.PendingEdits {
        var flushed = 0
        var discarded = 0
        override fun flush() { flushed++ }
        override fun discard() { discarded++ }
    }

    @Test
    fun `flush and discard without a registered editor do nothing`() {
        val sync = AccountsSync()
        sync.flushPending()
        sync.discardPending()
    }

    @Test
    fun `flush and discard reach the registered editor`() {
        val sync = AccountsSync()
        val edits = Edits()
        sync.register(edits)
        sync.flushPending()
        sync.flushPending()
        sync.discardPending()
        assertEquals(2, edits.flushed)
        assertEquals(1, edits.discarded)
    }

    @Test
    fun `unregister only removes the current editor`() {
        val sync = AccountsSync()
        val first = Edits()
        val second = Edits()
        sync.register(first)
        sync.register(second)
        sync.unregister(first)
        sync.flushPending()
        assertEquals(0, first.flushed)
        assertEquals(1, second.flushed)
        sync.unregister(second)
        sync.flushPending()
        assertEquals(1, second.flushed)
    }

    @Test
    fun `external change calls listeners`() {
        val sync = AccountsSync()
        var calls = 0
        sync.listeners += { calls++ }
        sync.listeners += { calls += 10 }
        sync.notifyExternalChange()
        assertEquals(11, calls)
    }

    @Test
    fun `external change is emitted on the flow`() = runTest(UnconfinedTestDispatcher()) {
        val sync = AccountsSync()
        var received = 0
        val job = launch { sync.externalChanges.first(); received++ }
        sync.notifyExternalChange()
        job.join()
        assertEquals(1, received)
    }
}
