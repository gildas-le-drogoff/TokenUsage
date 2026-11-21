package fr.gildasledrogoff.tokenusage.core.database

import android.os.Build
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class BalanceSnapshotDaoTest : DaoTestBase() {
    private val dao get() = db.balanceSnapshotDao()

    private fun snap(
        ts: Long,
        service: String = "claude",
        account: String = "a1",
        balance: Double = 1.0
    ) = BalanceSnapshotEntity(
        service = service, accountId = account, accountLabel = "L",
        currency = "USD", totalBalance = balance, timestamp = ts
    )

    @Test
    fun insertReturnsGeneratedIds() = runTest {
        val first = dao.insert(snap(1))
        val second = dao.insert(snap(2))
        assertTrue(second > first)
    }

    @Test
    fun snapshotsSinceFiltersByServiceAccountAndTimeOrderedAsc() = runTest {
        dao.insert(snap(30))
        dao.insert(snap(10))
        dao.insert(snap(20))
        dao.insert(snap(25, service = "other"))
        dao.insert(snap(26, account = "a2"))
        val result = dao.snapshotsSince("claude", "a1", 20)
        assertEquals(listOf(20L, 30L), result.map { it.timestamp })
    }

    @Test
    fun latestReturnsMostRecent() = runTest {
        dao.insert(snap(10, balance = 1.0))
        dao.insert(snap(50, balance = 5.0))
        dao.insert(snap(30, balance = 3.0))
        assertEquals(5.0, dao.latest("claude", "a1")?.totalBalance)
    }

    @Test
    fun latestIsNullWhenNoMatch() = runTest {
        dao.insert(snap(10))
        assertNull(dao.latest("claude", "unknown"))
    }

    @Test
    fun allSnapshotsOrderedAsc() = runTest {
        dao.insert(snap(3))
        dao.insert(snap(1, service = "x"))
        dao.insert(snap(2))
        assertEquals(listOf(1L, 2L, 3L), dao.allSnapshots().map { it.timestamp })
    }

    @Test
    fun deleteOlderThanRemovesStrictlyOlderAndReturnsCount() = runTest {
        dao.insert(snap(10))
        dao.insert(snap(20))
        dao.insert(snap(30))
        assertEquals(2, dao.deleteOlderThan(30))
        assertEquals(listOf(30L), dao.allSnapshots().map { it.timestamp })
    }
}
