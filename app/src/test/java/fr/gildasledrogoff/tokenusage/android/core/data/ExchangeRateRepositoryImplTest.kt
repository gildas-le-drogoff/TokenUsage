package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.DisplayCurrency
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.network.fx.ExchangeRateParsersTest
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.security.InMemorySharedPreferences
import fr.gildasledrogoff.tokenusage.core.security.SecureStorageFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ExchangeRateRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: EncryptedPrefsManager
    private lateinit var factoryPrefs: InMemorySharedPreferences
    private var now = 1_000_000L
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val factory = mock(SecureStorageFactory::class.java)
        factoryPrefs = InMemorySharedPreferences()
        `when`(factory.credentials).thenReturn(factoryPrefs)
        prefs = EncryptedPrefsManager(factory)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun repository(ttlMs: Long = 60_000L) = ExchangeRateRepositoryImpl(
        client = OkHttpClient(),
        json = json,
        prefsManager = prefs,
        frankfurterBaseUrl = server.url("/").toString(),
        ecbUrl = server.url("/ecb.xml").toString(),
        cacheTtlMs = ttlMs,
        nowMs = { now }
    )

    @Test
    fun `no display currency returns no rate and does no request`() = runTest {
        assertEquals(DisplayCurrency.ORIGINAL, prefs.getDisplayCurrency())
        assertNull(repository().displayRate("USD"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `legacy euro conversion keeps showing euros`() = runTest {
        factoryPrefs.edit().putString("eur_rate_source", "MANUAL").apply()
        prefs.setManualUsdEurRate(0.92)
        assertEquals(DisplayCurrency.EUR, prefs.getDisplayCurrency())
        assertEquals(0.92, repository().displayRate("USD")!!.rate, 1e-9)

        factoryPrefs.edit().putString("eur_rate_source", "OFF").apply()
        assertEquals(DisplayCurrency.ORIGINAL, prefs.getDisplayCurrency())
        assertEquals(FxRateSource.DEFAULT, prefs.getFxRateSource())
    }

    @Test
    fun `picking a source does not enable the conversion`() = runTest {
        prefs.setFxRateSource(FxRateSource.ECB)
        assertEquals(DisplayCurrency.ORIGINAL, prefs.getDisplayCurrency())
        assertNull(repository().displayRate("USD"))
    }

    @Test
    fun `remote source makes no request until the user accepts`() = runTest {
        prefs.setDisplayCurrency(DisplayCurrency.EUR)
        prefs.setFxRateSource(FxRateSource.FRANKFURTER)
        server.enqueue(MockResponse().setBody("""{"base":"USD","quote":"EUR","rate":0.9}"""))
        val repo = repository()

        assertFalse(prefs.isFxApiFetchAllowed())
        assertNull(repo.displayRate("USD"))
        assertEquals(0, server.requestCount)

        prefs.setFxApiFetchAllowed(true)
        assertEquals(0.9, repo.displayRate("USD")!!.rate, 1e-9)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `manual rates cover every pair they define`() = runTest {
        prefs.setDisplayCurrency(DisplayCurrency.EUR)
        prefs.setFxRateSource(FxRateSource.MANUAL)
        prefs.setManualUsdEurRate(0.92)
        val repo = repository()

        val rate = repo.displayRate("usd")
        assertNotNull(rate)
        assertEquals(0.92, rate!!.rate, 1e-9)
        assertEquals("EUR", rate.to)
        assertEquals(9.2, rate.convert(10.0), 1e-9)
        assertNull(repo.displayRate("CNY"))
        assertNull(repo.displayRate("EUR"))

        prefs.setManualUsdCnyRate(7.2)
        assertEquals(0.92 / 7.2, repo.displayRate("CNY")!!.rate, 1e-9)

        prefs.setDisplayCurrency(DisplayCurrency.CNY)
        assertEquals(7.2, repo.displayRate("USD")!!.rate, 1e-9)
        prefs.setDisplayCurrency(DisplayCurrency.USD)
        assertEquals(1.0 / 7.2, repo.displayRate("CNY")!!.rate, 1e-9)
        assertNull(repo.displayRate("USD"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `frankfurter rate is fetched then served from cache`() = runTest {
        prefs.setDisplayCurrency(DisplayCurrency.EUR)
        prefs.setFxRateSource(FxRateSource.FRANKFURTER)
        prefs.setFxApiFetchAllowed(true)
        server.enqueue(MockResponse().setBody("""{"date":"2026-11-15","base":"USD","quote":"EUR","rate":0.90285}"""))
        val repo = repository()

        val first = repo.displayRate("USD")
        assertEquals(0.90285, first!!.rate, 1e-9)
        assertEquals("2026-11-15", first.date)
        assertEquals("/v2/rate/usd/eur", server.takeRequest().path)

        now += 30_000L
        assertEquals(0.90285, repo.displayRate("USD")!!.rate, 1e-9)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `frankfurter crosses two euro quotes for a non-euro display currency`() = runTest {
        prefs.setDisplayCurrency(DisplayCurrency.CNY)
        prefs.setFxRateSource(FxRateSource.FRANKFURTER)
        prefs.setFxApiFetchAllowed(true)
        server.enqueue(MockResponse().setBody("""{"date":"2026-11-15","base":"USD","quote":"EUR","rate":0.9}"""))
        server.enqueue(MockResponse().setBody("""{"date":"2026-11-15","base":"CNY","quote":"EUR","rate":0.125}"""))
        val repo = repository()

        val rate = repo.displayRate("USD")!!
        assertEquals(7.2, rate.rate, 1e-9)
        assertEquals("USD", rate.from)
        assertEquals("CNY", rate.to)
        assertEquals("/v2/rate/usd/eur", server.takeRequest().path)
        assertEquals("/v2/rate/cny/eur", server.takeRequest().path)

        assertEquals(8.0, repo.displayRate("EUR")!!.rate, 1e-9)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `stale cache is used when the refetch fails`() = runTest {
        prefs.setDisplayCurrency(DisplayCurrency.EUR)
        prefs.setFxRateSource(FxRateSource.FRANKFURTER)
        prefs.setFxApiFetchAllowed(true)
        server.enqueue(MockResponse().setBody("""{"base":"USD","quote":"EUR","rate":0.9}"""))
        server.enqueue(MockResponse().setResponseCode(500))
        val repo = repository()

        assertEquals(0.9, repo.displayRate("USD")!!.rate, 1e-9)
        now += 120_000L
        assertEquals(0.9, repo.displayRate("USD")!!.rate, 1e-9)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `ecb rate is inverted and unknown currencies fail`() = runTest {
        prefs.setDisplayCurrency(DisplayCurrency.EUR)
        prefs.setFxRateSource(FxRateSource.ECB)
        prefs.setFxApiFetchAllowed(true)
        server.enqueue(MockResponse().setBody(ExchangeRateParsersTest.ECB_SAMPLE))
        server.enqueue(MockResponse().setBody(ExchangeRateParsersTest.ECB_SAMPLE))
        val repo = repository()

        val usd = repo.displayRate("USD")
        assertEquals(1.0 / 1.1052, usd!!.rate, 1e-9)
        assertEquals("2026-11-13", usd.date)
        assertEquals(FxRateSource.ECB, usd.source)

        val missing = repo.fetchLatest(FxRateSource.ECB, "XYZ", "EUR")
        assertTrue(missing is Result.Failure)
    }

    @Test
    fun `one ecb document serves both legs of a cross rate`() = runTest {
        server.enqueue(MockResponse().setBody(ExchangeRateParsersTest.ECB_SAMPLE))
        val repo = repository()

        val fetched = repo.fetchLatest(FxRateSource.ECB, "CNY", "USD")
        assertTrue(fetched is Result.Success)
        val rate = (fetched as Result.Success).value
        assertEquals(1, server.requestCount)

        val usdEur = repo.cachedRate(FxRateSource.ECB, "USD", "EUR")!!.rate
        val cnyEur = repo.cachedRate(FxRateSource.ECB, "CNY", "EUR")!!.rate
        assertEquals(1.0 / 1.1052, usdEur, 1e-9)
        assertEquals(cnyEur / usdEur, rate.rate, 1e-9)
        assertEquals(rate.rate, repo.cachedRate(FxRateSource.ECB, "CNY", "USD")!!.rate, 1e-9)
    }

    @Test
    fun `fetchLatest reports http errors`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = repository().fetchLatest(FxRateSource.FRANKFURTER, "USD", "EUR")
        assertTrue(result is Result.Failure)
    }
}
