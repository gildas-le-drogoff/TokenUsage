package fr.gildasledrogoff.tokenusage.core.status

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderStatusRepository @Inject constructor(
    private val clock: Clock,
    private val prefsManager: EncryptedPrefsManager
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()

    private val lastPolls = mutableMapOf<StatusSource, Instant>()
    private val bySource = MutableStateFlow<Map<StatusSource, ProviderStatus>>(emptyMap())
    private val _settings = MutableStateFlow(prefsManager.getStatusCheckSettings())
    val settings: StateFlow<StatusCheckSettings> = _settings.asStateFlow()
    private val _statuses = MutableStateFlow<Map<AiService, ProviderStatus>>(emptyMap())

    internal var fetcher: suspend (StatusSource, Instant) -> ProviderStatus? = { source, now -> fetch(source, now) }

    val statuses: StateFlow<Map<AiService, ProviderStatus>> = _statuses.asStateFlow()

    fun updateSettings(settings: StatusCheckSettings) {
        prefsManager.setStatusCheckSettings(settings)
        _settings.value = settings
        val usedSources = enabledSources(settings).keys
        bySource.update { current -> current.filterKeys { it in usedSources } }
        publish()
    }

    suspend fun refresh(force: Boolean = false) {
        mutex.withLock {
            val now = clock.instant()
            val sources = enabledSources(_settings.value).keys.filter { source ->
                force || lastPolls[source]?.let { now.isBefore(it.plusSeconds(MIN_INTERVAL_SECONDS)) } != true
            }
            if (sources.isEmpty()) return
            sources.forEach { lastPolls[it] = now }
            coroutineScope {
                sources.map { source ->
                    async {
                        val status = fetcher(source, now) ?: return@async
                        if (source !in enabledSources(_settings.value)) return@async
                        bySource.update { it + (source to status) }
                        publish()
                    }
                }.awaitAll()
            }
        }
    }

    private fun publish() {
        _statuses.update {
            val known = bySource.value
            enabledSources(_settings.value).flatMap { (source, services) ->
                val status = known[source] ?: return@flatMap emptyList()
                services.map { it to status }
            }.toMap()
        }
    }

    private fun enabledSources(settings: StatusCheckSettings): Map<StatusSource, List<AiService>> =
        AiService.entries.filter(settings::isEnabled)
            .mapNotNull { service -> service.statusSource()?.let { it to service } }
            .groupBy({ it.first }, { it.second })

    internal suspend fun fetch(source: StatusSource, now: Instant): ProviderStatus? = withContext(Dispatchers.IO) {
        try {
            val url = when (source) {
                is StatusSource.Statuspage -> source.pageUrl.trimEnd('/') + "/api/v2/status.json"
                is StatusSource.IncidentFeed -> source.feedUrl
            }
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body.string().orEmpty()
                when (source) {
                    is StatusSource.Statuspage -> parse(body, source.pageUrl)
                    is StatusSource.IncidentFeed -> IncidentFeedParser.parse(body, source.pageUrl, now)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val MIN_INTERVAL_SECONDS = 300L
        private const val TIMEOUT_SECONDS = 10L
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        internal fun parse(body: String, pageUrl: String): ProviderStatus? = try {
            val dto = json.decodeFromString<Dto>(body)
            ProviderStatus(
                indicator = StatusIndicator.fromStatuspage(dto.status?.indicator),
                description = dto.status?.description?.takeIf { it.isNotBlank() },
                updatedAt = dto.page?.updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
                pageUrl = pageUrl
            ).takeIf { dto.status != null }
        } catch (_: Exception) {
            null
        }
    }

    @Serializable
    private data class Dto(val page: Page? = null, val status: Status? = null) {
        @Serializable
        data class Page(@kotlinx.serialization.SerialName("updated_at") val updatedAt: String? = null)

        @Serializable
        data class Status(val indicator: String? = null, val description: String? = null)
    }
}
