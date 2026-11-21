package fr.gildasledrogoff.tokenusage.core.parity

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import fr.gildasledrogoff.tokenusage.di.NetworkModule
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit

class ParityServer : AutoCloseable {
    val server = MockWebServer().also { it.start() }
    private val client = OkHttpClient.Builder().build()
    private val contentType = "application/json".toMediaType()

    fun <T> service(type: Class<T>): T = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(client)
        .addConverterFactory(NetworkModule.provideJson().asConverterFactory(contentType))
        .build()
        .create(type)

    fun enqueue(code: Int, body: String = "", headers: Map<String, String> = emptyMap()) {
        val response = MockResponse().setResponseCode(code).setBody(body)
        headers.forEach { (k, v) -> response.addHeader(k, v) }
        server.enqueue(response)
    }

    override fun close() = server.shutdown()
}
