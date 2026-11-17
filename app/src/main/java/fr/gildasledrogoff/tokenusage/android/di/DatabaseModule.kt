package fr.gildasledrogoff.tokenusage.di

import android.content.Context
import androidx.room.Room
import fr.gildasledrogoff.tokenusage.core.database.BalanceSnapshotDao
import fr.gildasledrogoff.tokenusage.core.database.TokenUsageDatabase
import fr.gildasledrogoff.tokenusage.core.database.DatabaseKeyProvider
import fr.gildasledrogoff.tokenusage.core.database.SqlCipherDatabasePreparer
import fr.gildasledrogoff.tokenusage.core.security.IntegrityGuard
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val DATABASE_NAME = "tokenusage.db"

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyProvider: DatabaseKeyProvider,
        integrityGuard: IntegrityGuard
    ): TokenUsageDatabase {
        if (integrityGuard.isVaultLocked) {
            return Room.inMemoryDatabaseBuilder(context, TokenUsageDatabase::class.java).build()
        }

        System.loadLibrary("sqlcipher")
        val key = keyProvider.getOrCreate()
        SqlCipherDatabasePreparer.prepare(context, DATABASE_NAME, key)

        return Room.databaseBuilder(context, TokenUsageDatabase::class.java, DATABASE_NAME)
            .openHelperFactory(SupportOpenHelperFactory(key.rawKey, MemorySecurityHook, false))
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    
    private object MemorySecurityHook : SQLiteDatabaseHook {
        override fun preKey(connection: SQLiteConnection) = Unit
        override fun postKey(connection: SQLiteConnection) {
            connection.executeRaw("PRAGMA cipher_memory_security = ON", null, null)
        }
    }

    @Provides
    fun provideBalanceSnapshotDao(database: TokenUsageDatabase): BalanceSnapshotDao {
        return database.balanceSnapshotDao()
    }

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()
}
