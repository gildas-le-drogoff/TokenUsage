package fr.gildasledrogoff.tokenusage.core.backup

import fr.gildasledrogoff.tokenusage.core.database.BalanceSnapshotDao
import fr.gildasledrogoff.tokenusage.core.database.BalanceSnapshotEntity
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.status.ProviderStatusRepository
import fr.gildasledrogoff.tokenusage.core.status.StatusCheckSettings
import fr.gildasledrogoff.tokenusage.feature.settings.FakeDisplayPreferences
import fr.gildasledrogoff.tokenusage.feature.settings.inMemoryPrefsManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock

class ConfigBackupStatusCheckTest {
    private fun manager(): Pair<ConfigBackupManager, ProviderStatusRepository> {
        val prefs = inMemoryPrefsManager()
        val statusRepository = ProviderStatusRepository(Clock.systemUTC(), prefs).apply {
            fetcher = { _, _ -> null }
        }
        return ConfigBackupManager(prefs, EmptyDao, FakeDisplayPreferences(), statusRepository) to statusRepository
    }

    private suspend fun ConfigBackupManager.roundTrip(): BackupPayload =
        decryptContainer(parseZip(createZipBytes(PIN)), PIN)

    @Test
    fun `status check settings are exported and restored`() = runTest {
        val (source, sourceStatus) = manager()
        val saved = StatusCheckSettings(allProviders = true)
            .withProvider(AiService.CLAUDE, true)
            .withProvider(AiService.COPILOT, true)
        sourceStatus.updateSettings(saved)
        val payload = source.roundTrip()

        val (target, targetStatus) = manager()
        target.restore(payload)

        assertEquals(saved, targetStatus.settings.value)
    }

    @Test
    fun `disabled status check is restored as disabled`() = runTest {
        val (source, _) = manager()
        val payload = source.roundTrip()
        val (target, targetStatus) = manager()
        targetStatus.updateSettings(StatusCheckSettings(allProviders = true))

        target.restore(payload)

        assertEquals(StatusCheckSettings(), targetStatus.settings.value)
    }

    @Test
    fun `older backups keep the current setting`() = runTest {
        val (target, targetStatus) = manager()
        val current = StatusCheckSettings().withProvider(AiService.DEEPSEEK, true)
        targetStatus.updateSettings(current)

        target.restore(BackupPayload(exportedAtEpochMs = 0L))

        assertEquals(current, targetStatus.settings.value)
    }

    @Test
    fun `unknown providers in a backup are ignored`() = runTest {
        val (target, targetStatus) = manager()

        target.restore(
            BackupPayload(
                exportedAtEpochMs = 0L,
                statusCheckAllProviders = false,
                statusCheckProviders = listOf("OPENAI", "NOT_A_PROVIDER")
            )
        )

        assertEquals(setOf(AiService.OPENAI), targetStatus.settings.value.providers)
    }

    private object EmptyDao : BalanceSnapshotDao {
        override suspend fun insert(snapshot: BalanceSnapshotEntity): Long = 0L
        override suspend fun snapshotsSince(service: String, accountId: String, fromMillis: Long) =
            emptyList<BalanceSnapshotEntity>()
        override suspend fun latest(service: String, accountId: String): BalanceSnapshotEntity? = null
        override suspend fun allSnapshots() = emptyList<BalanceSnapshotEntity>()
        override suspend fun deleteOlderThan(cutoffMillis: Long): Int = 0
    }

    private companion object {
        const val PIN = "4242-status"
    }
}
