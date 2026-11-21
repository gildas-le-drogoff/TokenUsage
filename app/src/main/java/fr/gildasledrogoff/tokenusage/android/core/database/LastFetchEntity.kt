package fr.gildasledrogoff.tokenusage.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "last_fetch")
data class LastFetchEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val payload: String?
)
