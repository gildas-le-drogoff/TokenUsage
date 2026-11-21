package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendAmount
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import fr.gildasledrogoff.tokenusage.core.network.openai.OpenAiApiService
import fr.gildasledrogoff.tokenusage.core.network.openai.OpenAiDto
import fr.gildasledrogoff.tokenusage.core.provider.BaseQuotaProvider
import fr.gildasledrogoff.tokenusage.core.provider.apiKeyForm
import fr.gildasledrogoff.tokenusage.core.provider.httpError
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenAiRepositoryImpl @Inject constructor(
    private val apiService: OpenAiApiService,
    prefsManager: EncryptedPrefsManager,
    private val clock: Clock
) : BaseQuotaProvider(AiService.OPENAI, prefsManager) {
    override val form = apiKeyForm(
        labelRes = R.string.field_openai_admin_key,
        fieldHintRes = R.string.field_openai_admin_key_hint,
        hintRes = R.string.openai_hint
    ) { Credential.ApiKeyCredential(accessToken = it) }

    override suspend fun fetch(account: ServiceAccount): Result<QuotaInfo, AppError> {
        val apiKey = account.credential.accessToken.takeIf { it.isNotBlank() }
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.OPENAI))
        return fetchWithKey(apiKey)
    }

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
                    httpError(
                        AiService.OPENAI, response.code(),
                        authCodes = setOf(401, 403), unavailableCodes = setOf(502, 503),
                        authMessage = "Admin API key required"
                    )
                )
            }
            val body = response.body()
                ?: return Result.Failure(AppError.ParseError("Empty OpenAI costs response"))
            val pageData = body.data
                ?: return Result.Failure(AppError.ParseError("OpenAI costs page missing data"))
            val hasMore = body.hasMore
                ?: return Result.Failure(AppError.ParseError("OpenAI costs page missing has_more"))
            buckets += pageData
            if (!hasMore) break
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
            val monthStart =
                startOfToday.minus(Duration.ofDays(OpenAiDto.HISTORY_DAYS - 1L)).epochSecond
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
