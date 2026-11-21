package fr.gildasledrogoff.tokenusage.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class RetryInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        Thread.interrupted()
        server.shutdown()
    }

    private fun call(interceptor: RetryInterceptor): Int {
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        return client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.code }
    }

    private fun tooManyRequests(retryAfter: String? = "0") = MockResponse().setResponseCode(429).apply {
        if (retryAfter != null) setHeader("Retry-After", retryAfter)
    }

    @Test
    fun `a successful response is returned without retry`() {
        server.enqueue(MockResponse().setResponseCode(200))

        assertEquals(200, call(RetryInterceptor()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `other errors are not retried`() {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(500, call(RetryInterceptor()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a rate limited request is retried until it succeeds`() {
        server.enqueue(tooManyRequests())
        server.enqueue(tooManyRequests())
        server.enqueue(MockResponse().setResponseCode(200))

        assertEquals(200, call(RetryInterceptor(maxRetries = 3)))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `retries stop after the maximum and return the last 429`() {
        repeat(3) { server.enqueue(tooManyRequests()) }

        assertEquals(429, call(RetryInterceptor(maxRetries = 2)))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `retry after is capped by the maximum wait`() {
        server.enqueue(tooManyRequests(retryAfter = "3600"))
        server.enqueue(MockResponse().setResponseCode(200))
        val start = System.nanoTime()

        assertEquals(200, call(RetryInterceptor(maxWaitSeconds = 0)))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
    }

    @Test
    fun `exponential backoff is used without a usable retry after`() {
        server.enqueue(tooManyRequests(retryAfter = "soon"))
        server.enqueue(tooManyRequests(retryAfter = null))
        server.enqueue(MockResponse().setResponseCode(200))

        assertEquals(200, call(RetryInterceptor(maxWaitSeconds = 0)))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `an interrupted wait fails the call without retrying`() {
        server.enqueue(tooManyRequests(retryAfter = "1"))
        server.enqueue(MockResponse().setResponseCode(200))
        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor())
            .addInterceptor { chain ->
                chain.proceed(chain.request()).also { Thread.currentThread().interrupt() }
            }
            .build()

        val error = runCatching {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("Retry interrupted", error?.message)
        assertEquals(1, server.requestCount)
    }
}
