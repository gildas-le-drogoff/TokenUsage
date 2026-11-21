package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AccountBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.CreditBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendAmount
import fr.gildasledrogoff.tokenusage.core.domain.model.SpendPeriod
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.network.claudeplatform.ClaudePlatformApiService
import fr.gildasledrogoff.tokenusage.core.network.claudeplatform.ClaudePlatformDto
import fr.gildasledrogoff.tokenusage.core.provider.BaseQuotaProvider
import fr.gildasledrogoff.tokenusage.core.provider.apiKeyForm
import fr.gildasledrogoff.tokenusage.core.provider.httpError
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

@Singleton
class ClaudePlatformRepositoryImpl @Inject constructor(
    private val apiService: ClaudePlatformApiService,
    prefsManager: EncryptedPrefsManager,
    private val clock: Clock,
    private val balanceHistory: BalanceHistoryRepository
) : BaseQuotaProvider(AiService.CLAUDE_PLATFORM, prefsManager) {
    override val form = apiKeyForm(
        labelRes = R.string.field_claude_platform_cookie,
        fieldHintRes = R.string.field_claude_platform_cookie_hint,
        hintRes = R.string.claude_platform_hint
    ) { Credential.ApiKeyCredential(accessToken = it) }

    override val supportsPace = true

    override suspend fun fetch(account: ServiceAccount): Result<QuotaInfo, AppError> {
        val cookie = sessionCookie(account.credential.accessToken)
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.CLAUDE_PLATFORM))

        val orgResponse = apiService.getOrganizations(cookie)
        if (!orgResponse.isSuccessful) return Result.Failure(failure(orgResponse.code()))
        val candidates = orgResponse.body().orEmpty()
            .filter { it.isApi }
            .mapNotNull { org -> org.uuid?.takeIf(ORG_ID::matches)?.let { it to org.name?.trim() } }
        if (candidates.isEmpty()) {
            return Result.Failure(AppError.ParseError("No Claude Console API organization"))
        }

        for ((orgId, orgName) in candidates) {
            val response = apiService.getCredits(orgId, cookie)
            if (response.code() == 403) {
                continue
            }
            if (!response.isSuccessful) return Result.Failure(failure(response.code()))
            val credits = response.body()
                ?: return Result.Failure(AppError.ParseError("Empty Claude Console credit response"))
            if (credits.amount == null) {
                return Result.Failure(AppError.ParseError("Malformed Claude Console credit balance"))
            }
            val spend = runCatching { apiService.getCurrentSpend(orgId, cookie) }.getOrNull()
                ?.takeIf { it.isSuccessful }?.body()?.amount?.takeIf { it >= 0 }
            val info = toQuotaInfo(credits, spend, orgName, clock.instant())
            recordBalance(account, info)
            return Result.Success(info)
        }
        return Result.Failure(
            AppError.AuthError(AiService.CLAUDE_PLATFORM, isTerminal = false, message = "Billing access denied")
        )
    }

    private suspend fun recordBalance(account: ServiceAccount, info: QuotaInfo) {
        val balance = info.accountBalances.firstOrNull()?.balance ?: return
        try {
            balanceHistory.record(
                service = AiService.CLAUDE_PLATFORM,
                accountId = account.id,
                accountLabel = account.label,
                currency = balance.currency,
                totalBalance = balance.totalBalance
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun failure(code: Int): AppError = httpError(
        AiService.CLAUDE_PLATFORM, code,
        authCodes = setOf(401, 403), unavailableCodes = setOf(502, 503)
    )

    companion object {
        const val LABEL_API_CREDIT = "API credit"
        const val LABEL_PURCHASED_CREDIT = "Purchased credit"
        private val ORG_ID = Regex("[A-Za-z0-9-]{1,64}")
        private val SESSION_KEY = Regex("""(?:^|;\s*)sessionKey=""")

        fun sessionCookie(input: String): String? {
            val raw = input.trim().removePrefix("Cookie:").trim().takeIf { it.isNotEmpty() } ?: return null
            return if (SESSION_KEY.containsMatchIn(raw)) raw else "sessionKey=$raw"
        }

        private data class Grant(
            val purchased: Boolean,
            val program: String?,
            val granted: Double,
            val remaining: Double,
            val expiresAt: Instant?
        )

        fun toQuotaInfo(
            credits: ClaudePlatformDto.Credits,
            spendMinor: Long?,
            organization: String?,
            now: Instant
        ): QuotaInfo {
            val currency = credits.currency?.trim()?.uppercase()
                ?.takeIf { it.length == 3 } ?: ClaudePlatformDto.DEFAULT_CURRENCY
            val exponent = credits.balance?.credits?.exponent
                ?.takeIf { it in 0..ClaudePlatformDto.MAX_EXPONENT } ?: ClaudePlatformDto.DEFAULT_EXPONENT
            val scale = 10.0.pow(exponent)

            val grants = (credits.promoTranches.orEmpty().map { false to it } +
                credits.tranches.orEmpty().map { true to it })
                .mapNotNull { (purchased, t) ->
                    val granted = t.granted?.takeIf { it > 0 } ?: return@mapNotNull null
                    val expiresAt = t.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    if (expiresAt != null && !expiresAt.isAfter(now)) return@mapNotNull null
                    Grant(
                        purchased, t.programId,
                        granted / scale, (t.remaining ?: 0L).coerceAtLeast(0L) / scale, expiresAt
                    )
                }
                .sortedWith(compareBy(nullsLast()) { it.expiresAt })

            val subscription = grants.firstOrNull {
                it.program == ClaudePlatformDto.PROGRAM_SUBSCRIPTION && it.remaining > 0
            }
            val purchasedGrants = grants.filter { it.purchased }
            val purchased = purchasedGrants.firstOrNull { it.remaining > 0 } ?: purchasedGrants.firstOrNull()

            fun window(label: String, grant: Grant?) = grant?.let {
                UsageWindow(
                    label = label,
                    utilization = ((it.granted - it.remaining) / it.granted).coerceIn(0.0, 1.0),
                    resetsAt = it.expiresAt
                )
            }

            val balance = (credits.amount ?: 0L).coerceAtLeast(0L) / scale
            return QuotaInfo(
                service = AiService.CLAUDE_PLATFORM,
                windows = listOfNotNull(
                    window(LABEL_API_CREDIT, subscription),
                    window(LABEL_PURCHASED_CREDIT, purchased)
                ),
                extraUsage = null,
                tier = organization?.takeIf { it.isNotEmpty() },
                accountBalances = listOf(
                    AccountBalance(
                        label = "",
                        balance = CreditBalance(
                            currency = currency,
                            totalBalance = balance,
                            grantedBalance = null,
                            toppedUpBalance = null,
                            isAvailable = balance > 0.0
                        )
                    )
                ),
                spending = listOfNotNull(
                    spendMinor?.let { SpendAmount(SpendPeriod.THIS_MONTH, it / scale, currency) }
                ),
                fetchedAt = now
            )
        }
    }
}
