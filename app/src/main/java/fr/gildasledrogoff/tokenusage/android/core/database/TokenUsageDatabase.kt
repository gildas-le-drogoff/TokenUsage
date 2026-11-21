package fr.gildasledrogoff.tokenusage.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [BalanceSnapshotEntity::class, LastFetchEntity::class],
    version = 2,
    exportSchema = false
)
abstract class TokenUsageDatabase : RoomDatabase() {
    abstract fun balanceSnapshotDao(): BalanceSnapshotDao
    abstract fun lastFetchDao(): LastFetchDao
}
