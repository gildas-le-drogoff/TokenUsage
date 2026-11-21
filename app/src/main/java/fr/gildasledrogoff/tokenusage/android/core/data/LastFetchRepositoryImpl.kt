package fr.gildasledrogoff.tokenusage.core.data
import fr.gildasledrogoff.tokenusage.core.database.LastFetchDao
import fr.gildasledrogoff.tokenusage.core.database.LastFetchEntity
import fr.gildasledrogoff.tokenusage.core.domain.repository.CachedQuotaResult
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchSnapshot
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class LastFetchRepositoryImpl @Inject constructor(
    private val dao: LastFetchDao
) : LastFetchRepository {
    override suspend fun snapshot(): LastFetchSnapshot? {
        val row = dao.get(ROW_ID) ?: return null
        val results = row.payload?.let { LastFetchCacheCodec.decode(it) }
        return LastFetchSnapshot(
            fetchedAt = Instant.ofEpochMilli(row.timestamp),
            results = results
        )
    }
    override suspend fun record(fetchedAt: Instant, results: List<CachedQuotaResult>) {
        dao.upsert(
            LastFetchEntity(
                id = ROW_ID,
                timestamp = fetchedAt.toEpochMilli(),
                payload = LastFetchCacheCodec.encode(results)
            )
        )
    }
    private companion object {
        const val ROW_ID = "dashboard"
    }
}
