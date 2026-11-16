package fr.gildasledrogoff.tokenusage.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [BalanceSnapshotEntity::class],
    version = 1,
    exportSchema = false
)
abstract class TokenUsageDatabase : RoomDatabase() {
    abstract fun balanceSnapshotDao(): BalanceSnapshotDao
}
