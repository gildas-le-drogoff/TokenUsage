package fr.gildasledrogoff.tokenusage.core.workmanager

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SingleFlightTest {

    /** Même forme que la portée de production (SupervisorJob), sur le temps virtuel du test. */
    private fun TestScope.flightScope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    @Test
    fun `concurrent callers share one execution and the same result`() = runTest {
        val flight = SingleFlight<Int>(flightScope())
        val runs = AtomicInteger()

        val results = (1..5).map {
            async {
                flight.run {
                    runs.incrementAndGet()
                    delay(1_000)
                    42
                }
            }
        }.awaitAll()

        assertEquals(1, runs.get())
        assertEquals(List(5) { 42 }, results)
        assertEquals(1_000L, currentTime)
    }

    @Test
    fun `a call after completion starts a new execution`() = runTest {
        val flight = SingleFlight<Int>(flightScope())
        val runs = AtomicInteger()

        val first = flight.run { runs.incrementAndGet() }
        val second = flight.run { runs.incrementAndGet() }

        assertEquals(1, first)
        assertEquals(2, second)
    }

    @Test
    fun `a caller arriving while a run is in flight joins it instead of starting another`() = runTest {
        val flight = SingleFlight<String>(flightScope())
        val gate = CompletableDeferred<Unit>()
        val runs = AtomicInteger()

        val first = async { flight.run { runs.incrementAndGet(); gate.await(); "result" } }
        runCurrent()
        val late = async { flight.run { runs.incrementAndGet(); "should not run" } }
        runCurrent()
        gate.complete(Unit)

        assertEquals("result", first.await())
        assertEquals("result", late.await())
        assertEquals(1, runs.get())
    }

    @Test
    fun `a failure is delivered to every waiter and the next call retries`() = runTest {
        val flight = SingleFlight<Int>(flightScope())
        val gate = CompletableDeferred<Unit>()

        val a = async { runCatching { flight.run { gate.await(); error("boom") } } }
        val b = async { runCatching { flight.run { error("not executed") } } }
        runCurrent()
        gate.complete(Unit)

        assertEquals("boom", a.await().exceptionOrNull()?.message)
        assertEquals("boom", b.await().exceptionOrNull()?.message)
        assertEquals(7, flight.run { 7 })
    }

    @Test
    fun `cancelling one of several waiters does not stop the shared run`() = runTest {
        val flight = SingleFlight<Int>(flightScope())
        val gate = CompletableDeferred<Unit>()
        var completed = false

        val a = async { flight.run { gate.await(); completed = true; 1 } }
        val b = async { flight.run { 2 } }
        runCurrent()
        a.cancel()
        runCurrent()
        gate.complete(Unit)

        assertEquals(1, b.await())
        assertTrue(completed)
    }

    @Test
    fun `cancelling the only waiter cancels the run`() = runTest {
        val flight = SingleFlight<Int>(flightScope())
        var finished = false
        var cancelled = false

        val only = async {
            flight.run {
                try {
                    delay(10_000)
                    finished = true
                    1
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelled = true
                    throw e
                }
            }
        }
        runCurrent()
        only.cancel()
        advanceUntilIdle()

        assertTrue(cancelled)
        assertFalse(finished)
        // La mécanique reste utilisable après une annulation.
        assertEquals(5, flight.run { 5 })
    }

    @Test
    fun `waiter counts do not leak across executions`() = runTest {
        val flight = SingleFlight<Int>(flightScope())
        val runs = AtomicInteger()

        repeat(3) {
            val results = (1..3).map { async { flight.run { delay(10); runs.incrementAndGet() } } }.awaitAll()
            assertEquals(1, results.toSet().size)
        }

        assertEquals(3, runs.get())
    }

    @Test
    fun `exceptions propagate to the caller`() = runTest {
        val flight = SingleFlight<Int>(flightScope())

        val error = runCatching { flight.run { throw IllegalStateException("x") } }.exceptionOrNull()

        assertEquals("x", error?.message)
        assertTrue(error is IllegalStateException)
    }
}
