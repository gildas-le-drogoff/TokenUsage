package fr.gildasledrogoff.tokenusage.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BalanceSnapshotDao {
    @Insert
    suspend fun insert(snapshot: BalanceSnapshotEntity): Long

    @Query(
        """
        SELECT * FROM balance_snapshots
        WHERE service = :service AND accountId = :accountId AND timestamp >= :fromMillis
        ORDER BY timestamp ASC
        """
    )
    suspend fun snapshotsSince(
        service: String,
        accountId: String,
        fromMillis: Long
    ): List<BalanceSnapshotEntity>

    @Query(
        """
        SELECT * FROM balance_snapshots
        WHERE service = :service AND accountId = :accountId
        ORDER BY timestamp DESC
        LIMIT 1
        """
    )
    suspend fun latest(service: String, accountId: String): BalanceSnapshotEntity?

    @Query("SELECT * FROM balance_snapshots ORDER BY timestamp ASC")
    suspend fun allSnapshots(): List<BalanceSnapshotEntity>

    @Query("DELETE FROM balance_snapshots WHERE timestamp < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int
}
