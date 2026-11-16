package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.domain.repository.QuotaRepository
import fr.gildasledrogoff.tokenusage.core.network.copilot.CopilotApiService
import fr.gildasledrogoff.tokenusage.core.network.copilot.CopilotDto
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject


class CopilotRepositoryImpl @Inject constructor(
    private val apiService: CopilotApiService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(): Result<QuotaInfo, AppError> {
        val token = (prefsManager.loadCredential(AiService.COPILOT) as? Credential.ApiKeyCredential)
            ?.accessToken?.takeIf { it.isNotBlank() }
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.COPILOT))
        return try {
            fetchWithToken(token)
        } catch (e: IOException) {
            Result.Failure(AppError.NetworkError(e.message ?: "Network error", e))
        } catch (e: Exception) {
            Result.Failure(AppError.ParseError("${e::class.simpleName}: ${e.message}", e))
        }
    }

    override suspend fun validateCredential(): Result<Unit, AppError> = fetchQuota().map { }

    private suspend fun fetchWithToken(token: String): Result<QuotaInfo, AppError> {
        val response = apiService.getUser(authorization = "token $token")
        if (!response.isSuccessful) {
            return Result.Failure(
                when (response.code()) {
                    401 -> AppError.AuthError(AiService.COPILOT, isTerminal = true)
                    403, 404 -> AppError.ParseError("No Copilot access for this GitHub account")
                    429 -> AppError.RateLimited
                    503 -> AppError.ServiceUnavailable
                    else -> AppError.NetworkError("HTTP ${response.code()}")
                }
            )
        }
        val user = response.body()
            ?: return Result.Failure(AppError.ParseError("Empty Copilot usage response"))
        return toQuotaInfo(user)
    }

    companion object {
        const val LABEL_PREMIUM = "Premium requests"
        const val LABEL_CHAT = "Chat"
        const val LABEL_COMPLETIONS = "Completions"

        
        fun toQuotaInfo(user: CopilotDto.UserResponse, now: Instant = Instant.now()): Result<QuotaInfo, AppError> {
            if (user.accessTypeSku == CopilotDto.SKU_SUBSCRIPTION_ENDED) {
                return Result.Failure(AppError.ParseError("Copilot subscription ended"))
            }
            val resetsAt = parseResetDate(user.quotaResetDateUtc) ?: parseResetDate(user.quotaResetDate)
            val (premium, chat, completions) = selectSnapshots(user)
            val windows = listOfNotNull(
                premium?.utilization?.let { UsageWindow(LABEL_PREMIUM, it, resetsAt) },
                chat?.utilization?.let { UsageWindow(LABEL_CHAT, it, resetsAt) },
                completions?.utilization?.let { UsageWindow(LABEL_COMPLETIONS, it, resetsAt) }
            )
            val unlimited = listOfNotNull(premium, chat, completions).any { it.unlimited }
            if (windows.isEmpty() && !user.tokenBasedBilling && !unlimited) {
                return Result.Failure(AppError.ParseError("Copilot response contains no quota"))
            }
            return Result.Success(
                QuotaInfo(
                    service = AiService.COPILOT,
                    windows = windows,
                    extraUsage = null,
                    tier = planName(user.copilotPlan),
                    fetchedAt = now
                )
            )
        }

        
        private fun selectSnapshots(
            user: CopilotDto.UserResponse
        ): Triple<CopilotDto.QuotaSnapshot?, CopilotDto.QuotaSnapshot?, CopilotDto.QuotaSnapshot?> {
            val snapshots = user.snapshots.filterValues { !it.isPlaceholder }
            var premium = snapshots[CopilotDto.KEY_PREMIUM]
            var chat = snapshots[CopilotDto.KEY_CHAT]
            val completions = snapshots[CopilotDto.KEY_COMPLETIONS]

            if (premium == null || chat == null) {
                val others = snapshots - setOf(CopilotDto.KEY_PREMIUM, CopilotDto.KEY_CHAT, CopilotDto.KEY_COMPLETIONS)
                val chatLike = others.entries.firstOrNull { "chat" in it.key.lowercase() }
                val premiumLike = others.entries.firstOrNull { entry ->
                    val name = entry.key.lowercase()
                    "chat" !in name && ("premium" in name || "completion" in name || "code" in name)
                }
                premium = premium ?: premiumLike?.value
                chat = chat ?: chatLike?.value
                if (premium == null && chat == null && completions == null) {
                    chat = others.values.firstOrNull()
                }
            }
            return Triple(
                premium,
                preferred(chat, user.monthlySnapshot(CopilotDto.KEY_CHAT)),
                preferred(completions, user.monthlySnapshot(CopilotDto.KEY_COMPLETIONS))
            )
        }

        
        private fun preferred(
            direct: CopilotDto.QuotaSnapshot?,
            fallback: CopilotDto.QuotaSnapshot?
        ): CopilotDto.QuotaSnapshot? = when {
            direct?.utilization != null -> direct
            fallback?.utilization != null -> fallback
            else -> direct
        }

        
        fun planName(plan: String): String? = plan.trim().takeIf { it.isNotEmpty() }
            ?.split('_', ' ')
            ?.filter { it.isNotEmpty() }
            ?.joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

        
        fun parseResetDate(raw: String?): Instant? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { Instant.parse(value) }.getOrNull()
                ?: runCatching { LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
        }
    }
}
