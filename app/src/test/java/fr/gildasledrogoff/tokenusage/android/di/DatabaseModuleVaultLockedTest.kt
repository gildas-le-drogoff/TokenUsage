package fr.gildasledrogoff.tokenusage.di

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import fr.gildasledrogoff.tokenusage.core.database.DatabaseKeyProvider
import fr.gildasledrogoff.tokenusage.core.security.IntegrityGuard
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class DatabaseModuleVaultLockedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var database: fr.gildasledrogoff.tokenusage.core.database.TokenUsageDatabase? = null

    @After
    fun tearDown() {
        database?.close()
    }

    @Test
    fun `a locked vault gets an in-memory database and never asks for the key`() {
        val keyProvider = mock(DatabaseKeyProvider::class.java)
        val guard = mock(IntegrityGuard::class.java)
        `when`(guard.isVaultLocked).thenReturn(true)

        val db = DatabaseModule.provideDatabase(context, keyProvider, guard)
        database = db

        verifyNoInteractions(keyProvider)
        assertTrue(db.openHelper.databaseName == null || db.openHelper.databaseName == ":memory:")
        assertTrue(DatabaseModule.provideBalanceSnapshotDao(db) === db.balanceSnapshotDao())
        assertTrue(DatabaseModule.provideLastFetchDao(db) === db.lastFetchDao())
    }
}
