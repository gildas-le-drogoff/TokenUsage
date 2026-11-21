package fr.gildasledrogoff.tokenusage.core.data
import fr.gildasledrogoff.tokenusage.core.database.LastFetchDao
import fr.gildasledrogoff.tokenusage.core.database.LastFetchEntity
import fr.gildasledrogoff.tokenusage.core.domain.model.AccountBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.CreditBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.ExtraUsage
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.repository.CachedQuotaResult
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendAmount
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageBreakdownRow
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageSeverity
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
class LastFetchRepositoryImplTest {
    private class FakeLastFetchDao : LastFetchDao {
        val rows = mutableMapOf<String, LastFetchEntity>()
        override suspend fun get(id: String): LastFetchEntity? = rows[id]
        override suspend fun upsert(entity: LastFetchEntity) {
            rows[entity.id] = entity
        }
    }
    private val dao = FakeLastFetchDao()
    private val repository = LastFetchRepositoryImpl(dao)
    private fun quota(service: AiService, fetchedAt: Instant) = QuotaInfo(
        service = service,
        windows = listOf(
            UsageWindow("5h", 0.42, Instant.ofEpochMilli(1_234L), UsageSeverity.NORMAL),
            UsageWindow("week", 0.91, null, UsageSeverity.CRITICAL)
        ),
        extraUsage = ExtraUsage(true, 100.0, 42.5, 0.425, "USD"),
        tier = "pro",
        accountBalances = listOf(
            AccountBalance(
                label = "work",
                balance = CreditBalance("USD", 12.34, 20.0, null, true),
                error = AppError.ParseError("balance unavailable")
            )
        ),
        weeklyBreakdown = listOf(UsageBreakdownRow("code", 63.0)),
        spending = listOf(SpendAmount(SpendPeriod.LAST_30_DAYS, 7.89, "USD")),
        fetchedAt = fetchedAt
    )
    @Test
    fun `snapshot is null when nothing recorded`() = runTest {
        assertNull(repository.snapshot())
    }
    @Test
    fun `record then snapshot round trips results`() = runTest {
        val fetchedAt = Instant.ofEpochMilli(5_000_000L)
        val results = listOf(
            CachedQuotaResult(
                service = AiService.CLAUDE,
                accountLabel = "main",
                accountNumber = 2,
                quota = quota(AiService.CLAUDE, fetchedAt),
                error = null
            ),
            CachedQuotaResult(
                service = AiService.DEEPSEEK,
                quota = null,
                error = AppError.RateLimited
            ),
            CachedQuotaResult(
                service = AiService.GEMINI,
                quota = null,
                error = AppError.AuthError(AiService.GEMINI, isTerminal = true, message = "expired")
            ),
            CachedQuotaResult(
                service = AiService.ZAI,
                accountLabel = "alt",
                quota = null,
                error = AppError.ServiceUnavailable
            ),
            CachedQuotaResult(
                service = AiService.OPENAI,
                quota = null,
                error = AppError.NetworkError("offline")
            ),
            CachedQuotaResult(
                service = AiService.CLAUDE,
                accountLabel = "second",
                quota = null,
                error = AppError.TokenRevoked(AiService.CLAUDE)
            ),
            CachedQuotaResult(
                service = AiService.CODEX,
                quota = null,
                error = AppError.ClientUnsupported(AiService.CODEX)
            ),
            CachedQuotaResult(
                service = AiService.COPILOT,
                quota = null,
                error = AppError.CredentialNotFound(AiService.COPILOT)
            )
        )
        repository.record(fetchedAt, results)
        val snapshot = repository.snapshot()
        assertNotNull(snapshot)
        assertEquals(fetchedAt, snapshot!!.fetchedAt)
        assertEquals(results, snapshot.results)
    }
    @Test
    fun `second record replaces previous payload and timestamp`() = runTest {
        val first = Instant.ofEpochMilli(1_000L)
        val second = Instant.ofEpochMilli(2_000L)
        repository.record(first, listOf(CachedQuotaResult(AiService.CLAUDE, quota = quota(AiService.CLAUDE, first), error = null)))
        repository.record(second, emptyList())
        val snapshot = repository.snapshot()
        assertEquals(second, snapshot!!.fetchedAt)
        assertEquals(emptyList<CachedQuotaResult>(), snapshot.results)
    }
    @Test
    fun `corrupt payload yields snapshot without results`() = runTest {
        dao.upsert(LastFetchEntity(id = "dashboard", timestamp = 9_999L, payload = "{not json"))
        val snapshot = repository.snapshot()
        assertNotNull(snapshot)
        assertEquals(Instant.ofEpochMilli(9_999L), snapshot!!.fetchedAt)
        assertNull(snapshot.results)
    }
}
