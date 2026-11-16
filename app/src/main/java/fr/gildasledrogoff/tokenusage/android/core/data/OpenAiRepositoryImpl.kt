package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendAmount
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import fr.gildasledrogoff.tokenusage.core.domain.repository.QuotaRepository
import fr.gildasledrogoff.tokenusage.core.network.openai.OpenAiApiService
import fr.gildasledrogoff.tokenusage.core.network.openai.OpenAiDto
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject


class OpenAiRepositoryImpl @Inject constructor(
    private val apiService: OpenAiApiService,
    private val prefsManager: EncryptedPrefsManager,
    private val clock: Clock
) : QuotaRepository {

    override suspend fun fetchQuota(): Result<QuotaInfo, AppError> {
        val apiKey = (prefsManager.loadCredential(AiService.OPENAI) as? Credential.ApiKeyCredential)
            ?.accessToken?.takeIf { it.isNotBlank() }
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.OPENAI))
        return try {
            fetchWithKey(apiKey)
        } catch (e: IOException) {
            Result.Failure(AppError.NetworkError(e.message ?: "Network error", e))
        } catch (e: Exception) {
            Result.Failure(AppError.ParseError("${e::class.simpleName}: ${e.message}", e))
        }
    }

    override suspend fun validateCredential(): Result<Unit, AppError> = fetchQuota().map { }

    private suspend fun fetchWithKey(apiKey: String): Result<QuotaInfo, AppError> {
        val now = clock.instant()
        val startOfToday = now.truncatedTo(ChronoUnit.DAYS)
        val start = startOfToday.minus(Duration.ofDays(OpenAiDto.HISTORY_DAYS - 1L))

        val end = startOfToday.plus(Duration.ofDays(1))

        val buckets = mutableListOf<OpenAiDto.CostBucket>()
        var page: String? = null
        val seenPages = mutableSetOf<String>()
        while (true) {
            val response = apiService.getCosts(
                authorization = "Bearer $apiKey",
                startTime = start.epochSecond,
                endTime = end.epochSecond,
                page = page
            )
            if (!response.isSuccessful) {
                return Result.Failure(
                    when (response.code()) {
                        401, 403 -> AppError.AuthError(AiService.OPENAI, isTerminal = true, message = "Admin API key required")
                        429 -> AppError.RateLimited
                        502, 503 -> AppError.ServiceUnavailable
                        else -> AppError.NetworkError("HTTP ${response.code()}")
                    }
                )
            }
            val body = response.body()
                ?: return Result.Failure(AppError.ParseError("Empty OpenAI costs response"))
            buckets += body.data
            if (!body.hasMore) break
            page = body.nextPage?.trim()?.takeIf { it.isNotEmpty() }
                ?: return Result.Failure(AppError.ParseError("OpenAI costs pagination cursor missing"))
            if (!seenPages.add(page)) {
                return Result.Failure(AppError.ParseError("OpenAI costs pagination cursor repeated"))
            }
            if (seenPages.size >= OpenAiDto.MAX_PAGES) {
                return Result.Failure(
                    AppError.ParseError("OpenAI costs pagination exceeded ${OpenAiDto.MAX_PAGES} pages")
                )
            }
        }
        return Result.Success(toQuotaInfo(buckets, now))
    }

    companion object {
        const val TIER = "Admin API"

        
        fun toQuotaInfo(buckets: List<OpenAiDto.CostBucket>, now: Instant): QuotaInfo {
            val startOfToday = now.truncatedTo(ChronoUnit.DAYS)
            val weekStart = startOfToday.minus(Duration.ofDays(6)).epochSecond
            val monthStart = startOfToday.minus(Duration.ofDays(OpenAiDto.HISTORY_DAYS - 1L)).epochSecond
            val todayStart = startOfToday.epochSecond

            fun total(from: Long) = buckets
                .filter { it.startTime >= from && it.startTime <= now.epochSecond }
                .sumOf { bucket -> bucket.results.sumOf { it.amount?.value ?: 0.0 } }

            val currency = buckets.asSequence()
                .flatMap { it.results }
                .mapNotNull { it.amount?.currency?.takeIf(String::isNotBlank) }
                .firstOrNull()?.uppercase() ?: "USD"

            return QuotaInfo(
                service = AiService.OPENAI,
                windows = emptyList(),
                extraUsage = null,
                tier = TIER,
                spending = listOf(
                    SpendAmount(SpendPeriod.TODAY, total(todayStart), currency),
                    SpendAmount(SpendPeriod.LAST_7_DAYS, total(weekStart), currency),
                    SpendAmount(SpendPeriod.LAST_30_DAYS, total(monthStart), currency)
                ),
                fetchedAt = now
            )
        }
    }
}
