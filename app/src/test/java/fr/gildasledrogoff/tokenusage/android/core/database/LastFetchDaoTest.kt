package fr.gildasledrogoff.tokenusage.core.database

import android.os.Build
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class LastFetchDaoTest : DaoTestBase() {
    private val dao get() = db.lastFetchDao()

    @Test
    fun getReturnsNullWhenAbsent() = runTest {
        assertNull(dao.get("missing"))
    }

    @Test
    fun upsertThenGetRoundTrips() = runTest {
        dao.upsert(LastFetchEntity("claude", 42, "{}"))
        assertEquals(LastFetchEntity("claude", 42, "{}"), dao.get("claude"))
    }

    @Test
    fun upsertReplacesExistingRow() = runTest {
        dao.upsert(LastFetchEntity("claude", 1, "old"))
        dao.upsert(LastFetchEntity("claude", 2, null))
        assertEquals(LastFetchEntity("claude", 2, null), dao.get("claude"))
    }
}
