package fr.gildasledrogoff.tokenusage.core.workmanager

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TokenRefreshLockTest {

    @Test
    fun `refreshes never overlap`() = runTest {
        val inside = AtomicInteger()
        var maxInside = 0

        (1..6).map {
            async {
                TokenRefreshLock.withLock {
                    maxInside = maxOf(maxInside, inside.incrementAndGet())
                    delay(100)
                    inside.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(1, maxInside)
        assertEquals(600L, currentTime)
    }

    @Test
    fun `lock is released after an exception`() = runTest {
        runCatching { TokenRefreshLock.withLock { error("network down") } }

        assertFalse(TokenRefreshLock.isLocked)
        assertEquals("ok", TokenRefreshLock.withLock { "ok" })
    }

    @Test
    fun `a waiter observes the credential written by the previous holder`() = runTest {
        var storedRefreshToken = "rt-1"
        val exchanges = mutableListOf<String>()

        // Scénario du bug : le worker et un fetch voient tous deux "rt-1" expiré.
        // Avec le verrou et une relecture, le second constate que le token a déjà tourné.
        suspend fun refreshIfStillExpired() = TokenRefreshLock.withLock {
            val current = storedRefreshToken
            if (current == "rt-1") {
                exchanges += current
                delay(50)
                storedRefreshToken = "rt-2"
            }
        }

        listOf(async { refreshIfStillExpired() }, async { refreshIfStillExpired() }).awaitAll()

        assertEquals(listOf("rt-1"), exchanges)
        assertEquals("rt-2", storedRefreshToken)
        assertTrue(!TokenRefreshLock.isLocked)
    }
}
