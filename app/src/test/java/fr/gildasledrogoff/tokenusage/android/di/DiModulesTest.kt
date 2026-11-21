package fr.gildasledrogoff.tokenusage.di

import fr.gildasledrogoff.tokenusage.core.alert.AlertPreferencesStore
import fr.gildasledrogoff.tokenusage.core.alert.AlertRule
import fr.gildasledrogoff.tokenusage.core.data.BalanceHistoryRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.DisplayPreferencesRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.ExchangeRateRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.LastFetchRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.database.BalanceSnapshotDao
import fr.gildasledrogoff.tokenusage.core.database.LastFetchDao
import fr.gildasledrogoff.tokenusage.core.database.TokenUsageDatabase
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.network.AndroidNetworkMonitor
import fr.gildasledrogoff.tokenusage.core.provider.QuotaProvider
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.security.InMemorySharedPreferences
import fr.gildasledrogoff.tokenusage.core.security.SecureStorageFactory
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.ZoneId

class DiModulesTest {

    @Test
    fun `repository bindings return the implementation they are given`() {
        val display = mock(DisplayPreferencesRepositoryImpl::class.java)
        val history = mock(BalanceHistoryRepositoryImpl::class.java)
        val lastFetch = mock(LastFetchRepositoryImpl::class.java)

        assertSame(display, RepositoryModule.provideDisplayPreferencesRepository(display))
        assertSame(history, RepositoryModule.provideBalanceHistoryRepository(history))
        assertSame(lastFetch, RepositoryModule.provideLastFetchRepository(lastFetch))
    }

    @Test
    fun `exchange rate repository is built from the dedicated client`() {
        val repository = RepositoryModule.provideExchangeRateRepository(
            client = OkHttpClient(),
            json = Json,
            prefsManager = mock(EncryptedPrefsManager::class.java)
        )

        assertTrue(repository is ExchangeRateRepositoryImpl)
    }

    

    @Test
    fun `encrypted prefs manager is created from the storage factory`() {
        val factory = mock(SecureStorageFactory::class.java)
        `when`(factory.credentials).thenReturn(InMemorySharedPreferences())

        val manager = SecurityModule.provideEncryptedPrefsManager(factory)
        manager.setRefreshInterval(15)

        assertEquals(15L, manager.getRefreshInterval())
    }


    @Test
    fun `alert store reads its preferences lazily from the credentials store`() {
        val factory = mock(SecureStorageFactory::class.java)
        val prefs = InMemorySharedPreferences()
        `when`(factory.credentials).thenReturn(prefs)

        val store = AlertModule.provideAlertPreferencesStore(factory)
        verify(factory, never()).credentials

        store.setServiceRule(AiService.CLAUDE, AlertRule(quotaThreshold = 0.5))

        verify(factory, times(1)).credentials
        assertEquals(0.5, store.currentConfig().ruleFor(AiService.CLAUDE, "CLAUDE:x").quotaThreshold!!, 1e-9)
        assertFalse(prefs.data.isEmpty())
    }


    @Test
    fun `configured services follow the stored credentials`() {
        val prefs = mock(EncryptedPrefsManager::class.java)
        `when`(prefs.hasCredential(AiService.CLAUDE)).thenReturn(true)
        `when`(prefs.hasCredential(AiService.CODEX)).thenReturn(false)

        val source = DiagnosticsModule.provideConfiguredServices(prefs)

        assertTrue(source.isConfigured(AiService.CLAUDE))
        assertFalse(source.isConfigured(AiService.CODEX))
    }


    @Test
    fun `clock uses the system default zone`() {
        val clock = DatabaseModule.provideClock()

        assertEquals(ZoneId.systemDefault(), clock.zone)
    }

    @Test
    fun `daos are taken from the database`() {
        val database = mock(TokenUsageDatabase::class.java)
        val snapshotDao = mock(BalanceSnapshotDao::class.java)
        val lastFetchDao = mock(LastFetchDao::class.java)
        `when`(database.balanceSnapshotDao()).thenReturn(snapshotDao)
        `when`(database.lastFetchDao()).thenReturn(lastFetchDao)

        assertSame(snapshotDao, DatabaseModule.provideBalanceSnapshotDao(database))
        assertSame(lastFetchDao, DatabaseModule.provideLastFetchDao(database))
    }


    @Test
    fun `network monitor binding returns the android implementation`() {
        val monitor = mock(AndroidNetworkMonitor::class.java)

        assertSame(monitor, NetworkModule.provideNetworkMonitor(monitor))
    }


    @Test
    fun `provider module binds exactly one quota provider per service`() {
        val bindings = ProviderModule::class.java.declaredMethods
            .filter { !it.isSynthetic }

        assertEquals(AiService.entries.size, bindings.size)
        assertTrue(bindings.all { QuotaProvider::class.java.isAssignableFrom(it.returnType) })
        assertEquals(bindings.size, bindings.map { it.parameterTypes.single() }.toSet().size)
        bindings.forEach { assertNotNull(it.parameterTypes.single()) }
    }
}
