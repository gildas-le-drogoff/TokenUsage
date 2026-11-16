package fr.gildasledrogoff.tokenusage.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey


@Entity(
    tableName = "balance_snapshots",
    indices = [Index(value = ["service", "accountId", "timestamp"])]
)
data class BalanceSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    
    val service: String,
    
    val accountId: String,
    
    val accountLabel: String,
    
    val currency: String,
    val totalBalance: Double,
    
    val timestamp: Long
)
