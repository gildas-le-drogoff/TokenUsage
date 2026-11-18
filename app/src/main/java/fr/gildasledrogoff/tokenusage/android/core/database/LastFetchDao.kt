package fr.gildasledrogoff.tokenusage.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LastFetchDao {

    @Query("SELECT * FROM last_fetch WHERE id = :id")
    suspend fun get(id: String): LastFetchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LastFetchEntity)
}
