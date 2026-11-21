package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import fr.gildasledrogoff.tokenusage.core.network.openai.OpenAiApiService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import retrofit2.Retrofit
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class OpenAiRepositoryImplTest {

    private lateinit var server: MockWebServer
    private lateinit var prefsManager: EncryptedPrefsManager
    private lateinit var repository: OpenAiRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    private val now = Instant.parse("2026-11-15T15:00:00Z")
    private val today = Instant.parse("2026-11-15T00:00:00Z").epochSecond
    private val day = 86_400L

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenAiApiService::class.java)
        prefsManager = mock(EncryptedPrefsManager::class.java)
        `when`(prefsManager.getAccounts(AiService.OPENAI)).thenReturn(listOf(ServiceAccount("id-1", "", Credential.ApiKeyCredential("sk-admin-x"))))
        repository = OpenAiRepositoryImpl(api, prefsManager, Clock.fixed(now, ZoneOffset.UTC))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun bucket(start: Long, vararg amounts: Double) =
        """{"start_time":$start,"end_time":${start + day},"results":[${
            amounts.joinToString(",") { """{"amount":{"value":$it,"currency":"usd"},"line_item":null}""" }
        }]}"""

    @Test
    fun `sums daily costs across pages per period`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"data":[${bucket(today - 20 * day, 10.0)},${bucket(today - 3 * day, 2.0, 0.5)}],
                    "has_more":true,"next_page":"page_2"}"""
            )
        )
        server.enqueue(
            MockResponse().setBody("""{"data":[${bucket(today, 1.25)}],"has_more":false,"next_page":null}""")
        )

        val quota = (repository.fetchQuota() as Result.Success).value

        val byPeriod = quota.spending.associate { it.period to it.amount }
        assertEquals(1.25, byPeriod.getValue(SpendPeriod.TODAY), 1e-9)
        assertEquals(3.75, byPeriod.getValue(SpendPeriod.LAST_7_DAYS), 1e-9)
        assertEquals(13.75, byPeriod.getValue(SpendPeriod.LAST_30_DAYS), 1e-9)
        assertEquals("USD", quota.spending.first().currency)
        assertTrue(quota.windows.isEmpty())

        val first = server.takeRequest()
        assertEquals("Bearer sk-admin-x", first.getHeader("Authorization"))
        val url = first.requestUrl!!
        assertEquals("/v1/organization/costs", url.encodedPath)
        assertEquals((today - 29 * day).toString(), url.queryParameter("start_time"))
        assertEquals((today + day).toString(), url.queryParameter("end_time"))
        assertEquals("1d", url.queryParameter("bucket_width"))
        assertEquals("page_2", server.takeRequest().requestUrl!!.queryParameter("page"))
    }

    @Test
    fun `truncated pagination is an error instead of a partial total`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"data":[${bucket(today, 1.0)}],"has_more":true,"next_page":null}""")
        )

        val error = (repository.fetchQuota() as Result.Failure).error

        assertTrue(error is AppError.ParseError)
    }

    @Test
    fun `repeated pagination cursor is an error instead of a loop`() = runTest {
        repeat(2) {
            server.enqueue(
                MockResponse().setBody("""{"data":[${bucket(today, 1.0)}],"has_more":true,"next_page":"again"}""")
            )
        }

        val error = (repository.fetchQuota() as Result.Failure).error

        assertTrue(error is AppError.ParseError)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `non admin key is a terminal auth error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val error = (repository.fetchQuota() as Result.Failure).error

        assertTrue(error is AppError.AuthError && error.isTerminal)
    }
}
