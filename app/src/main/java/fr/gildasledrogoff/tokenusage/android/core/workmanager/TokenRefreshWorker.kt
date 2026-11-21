package fr.gildasledrogoff.tokenusage.core.workmanager
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import fr.gildasledrogoff.tokenusage.core.diagnostics.DiagnosticsStore
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.Credential
import fr.gildasledrogoff.tokenusage.core.domain.model.ServiceAccount
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.network.codex.CodexDto
import fr.gildasledrogoff.tokenusage.core.network.codex.CodexTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiDto
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.widget.WidgetPrefsManager
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
@HiltWorker
class TokenRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val claudeTokenRefreshService: ClaudeTokenRefreshService,
    private val codexTokenRefreshService: CodexTokenRefreshService,
    private val geminiTokenRefreshService: GeminiTokenRefreshService,
    private val prefsManager: EncryptedPrefsManager,
    private val widgetPrefs: WidgetPrefsManager,
    private val widgetQuotaPublisher: WidgetQuotaPublisher,
    private val diagnosticsStore: DiagnosticsStore
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result = TokenRefreshLock.withLock {
        val results = coroutineScope {
            AiService.entries
                .flatMap { service -> prefsManager.getAccounts(service).map { service to it } }
                .map { (service, account) -> async { refreshIfNeeded(service, account) } }
                .awaitAll()
        }
        val decision = WorkRetryPolicy.decide(allSucceeded = results.all { it }, runAttemptCount = runAttemptCount)
        diagnosticsStore.recordWorkerRun(WorkManagerInitializer.TOKEN_WORK_NAME, decision.label, runAttemptCount)
        if (decision == WorkRetryPolicy.Decision.RETRY) Result.retry() else Result.success()
    }
    private suspend fun refreshIfNeeded(service: AiService, account: ServiceAccount): Boolean =
        when (val credential = account.credential) {
            is Credential.ClaudeCredential -> refreshClaude(service, account.id, credential)
            is Credential.CodexCredential -> refreshCodex(service, account.id, credential)
            is Credential.GeminiCredential -> refreshGemini(service, account.id, credential)
            is Credential.ClaudeWebCredential,
            is Credential.DeepseekCredential,
            is Credential.ZaiCredential,
            is Credential.ApiKeyCredential -> true
        }
    private suspend fun refreshClaude(
        service: AiService,
        accountId: String,
        credential: Credential.ClaudeCredential
    ): Boolean {
        val expiresAt = credential.expiresAt ?: return true
        if (Instant.now().isBefore(expiresAt.minusSeconds(REFRESH_BUFFER_SECONDS))) return true
        val refreshToken = credential.refreshToken ?: return false
        return try {
            val response = claudeTokenRefreshService.refreshToken(refreshToken = refreshToken)
            if (response.isSuccessful) {
                val body = response.body() ?: return false
                prefsManager.updateAccountCredential(
                    service,
                    accountId,
                    Credential.ClaudeCredential(
                        accessToken = body.accessToken,
                        refreshToken = body.refreshToken ?: refreshToken,
                        expiresAt = Instant.now().plusSeconds(body.expiresIn.toLong()),
                        scopes = credential.scopes,
                        rateLimitTier = credential.rateLimitTier
                    )
                )
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
    private suspend fun refreshCodex(
        service: AiService,
        accountId: String,
        credential: Credential.CodexCredential
    ): Boolean {
        return try {
            val request = CodexDto.TokenRefreshRequest(refreshToken = credential.refreshToken)
            val response = codexTokenRefreshService.refreshToken(request)
            if (response.isSuccessful) {
                val body = response.body() ?: return false
                prefsManager.updateAccountCredential(
                    service,
                    accountId,
                    Credential.CodexCredential(
                        accessToken = body.accessToken,
                        refreshToken = body.refreshToken ?: credential.refreshToken,
                        accountId = credential.accountId
                    )
                )
                true
            } else {
                val errorBody = response.errorBody()?.string() ?: ""
                val isTerminal = response.code() == 401 ||
                    CodexDto.TERMINAL_ERROR_CODES.any { errorBody.contains(it) }
                if (isTerminal) {
                    prefsManager.deleteAccount(service, accountId)
                    if (!prefsManager.hasCredential(service)) widgetPrefs.removeServiceEverywhere(service)
                    widgetQuotaPublisher.refreshWidgets()
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }
    private suspend fun refreshGemini(
        service: AiService,
        accountId: String,
        credential: Credential.GeminiCredential
    ): Boolean {
        if (System.currentTimeMillis() < credential.expiresAtMs - REFRESH_BUFFER_SECONDS * 1000) return true
        return try {
            val response = geminiTokenRefreshService.refreshToken(
                refreshToken = credential.refreshToken,
                clientId = credential.oauthClientId,
                clientSecret = credential.oauthClientSecret
            )
            if (response.isSuccessful) {
                val body = response.body() ?: return false
                val expiresIn = body.expiresIn ?: 3600
                prefsManager.updateAccountCredential(
                    service,
                    accountId,
                    Credential.GeminiCredential(
                        accessToken = body.accessToken,
                        refreshToken = body.refreshToken ?: credential.refreshToken,
                        expiresAtMs = System.currentTimeMillis() + (expiresIn * 1000L),
                        oauthClientId = credential.oauthClientId,
                        oauthClientSecret = credential.oauthClientSecret,
                        hostedDomain = GeminiDto.refreshedHostedDomain(body.idToken, credential.hostedDomain)
                    )
                )
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
    companion object {
        const val REFRESH_BUFFER_SECONDS = 600L
    }
}
