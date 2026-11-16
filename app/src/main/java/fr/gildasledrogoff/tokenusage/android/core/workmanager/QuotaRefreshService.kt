package fr.gildasledrogoff.tokenusage.core.workmanager

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.repository.ClaudeQuotaRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.MultiAccountQuotaRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.QuotaRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ZaiRepository
import fr.gildasledrogoff.tokenusage.core.notification.QuotaNotificationService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.tile.QuotaTileService
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import fr.gildasledrogoff.tokenusage.di.ClaudeRepository
import fr.gildasledrogoff.tokenusage.di.CodexRepository
import fr.gildasledrogoff.tokenusage.di.CopilotRepository
import fr.gildasledrogoff.tokenusage.di.DeepseekRepository
import fr.gildasledrogoff.tokenusage.di.GeminiRepository
import fr.gildasledrogoff.tokenusage.di.OpenAiRepository
import fr.gildasledrogoff.tokenusage.di.OpenRouterRepository
import fr.gildasledrogoff.tokenusage.di.ZaiRepositoryQualifier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class QuotaRefreshService @Inject constructor(
    @ApplicationContext private val context: Context,
    @ClaudeRepository private val claudeRepository: ClaudeQuotaRepository,
    @CodexRepository private val codexRepository: QuotaRepository,
    @GeminiRepository private val geminiRepository: QuotaRepository,
    @DeepseekRepository private val deepseekRepository: MultiAccountQuotaRepository,
    @ZaiRepositoryQualifier private val zaiRepository: ZaiRepository,
    @CopilotRepository private val copilotRepository: QuotaRepository,
    @OpenRouterRepository private val openRouterRepository: QuotaRepository,
    @OpenAiRepository private val openAiRepository: QuotaRepository,
    private val prefsManager: EncryptedPrefsManager,
    private val demoData: DemoDataRepository,
    private val notificationService: QuotaNotificationService,
    private val widgetQuotaPublisher: WidgetQuotaPublisher
) {

    
    data class Outcome(
        val quotas: List<QuotaInfo>,
        val anyServiceConfigured: Boolean
    )

    
    suspend fun refreshNow(): Outcome {
        
        if (demoData.isEnabled()) {
            val samples = demoData.sampleQuotas().mapNotNull { quota ->
                when (val result = quota.result) {
                    is Result.Success -> result.value
                    is Result.Failure -> null
                }
            }
            if (samples.isNotEmpty()) widgetQuotaPublisher.publish(samples)
            return Outcome(samples, anyServiceConfigured = true)
        }

        val repos = buildList {
            if (prefsManager.hasCredential(AiService.CLAUDE)) add(AiService.CLAUDE to claudeRepository)
            if (prefsManager.hasCredential(AiService.CODEX)) add(AiService.CODEX to codexRepository)
            if (prefsManager.hasCredential(AiService.GEMINI)) add(AiService.GEMINI to geminiRepository)
            if (prefsManager.hasCredential(AiService.DEEPSEEK)) add(AiService.DEEPSEEK to deepseekRepository)
            if (prefsManager.hasCredential(AiService.ZAI)) add(AiService.ZAI to zaiRepository)
            if (prefsManager.hasCredential(AiService.COPILOT)) add(AiService.COPILOT to copilotRepository)
            if (prefsManager.hasCredential(AiService.OPENROUTER)) add(AiService.OPENROUTER to openRouterRepository)
            if (prefsManager.hasCredential(AiService.OPENAI)) add(AiService.OPENAI to openAiRepository)
        }

        if (repos.isEmpty()) return Outcome(emptyList(), anyServiceConfigured = false)

        return try {
            val quotas = coroutineScope {
                repos.map { (_, repo) ->
                    async {
                        withTimeoutOrNull(REPO_TIMEOUT_MS) { repo.fetchQuota() }
                            ?: Result.Failure(AppError.NetworkError("manual refresh timeout"))
                    }
                }.awaitAll()
            }

            val successfulQuotas = quotas.mapNotNull { result ->
                when (result) {
                    is Result.Success -> result.value
                    is Result.Failure -> null
                }
            }

            if (successfulQuotas.isNotEmpty()) {
                widgetQuotaPublisher.publish(successfulQuotas)

                if (prefsManager.isNotificationsEnabled()) {
                    notificationService.showQuotaNotification(successfulQuotas)
                    checkForResets(successfulQuotas)
                }
            }

            TileService.requestListeningState(
                context,
                ComponentName(context, QuotaTileService::class.java)
            )

            Outcome(successfulQuotas, anyServiceConfigured = true)
        } catch (_: Exception) {
            Outcome(emptyList(), anyServiceConfigured = true)
        }
    }

    private fun checkForResets(quotas: List<QuotaInfo>) {
        val now = Instant.now()
        for (quota in quotas) {
            val previousResetTimes = prefsManager.loadResetTimes(quota.service)

            for (window in quota.windows) {
                val previousResetAt = previousResetTimes[window.label] ?: continue
                if (previousResetAt.isBefore(now) && window.resetsAt != null && window.resetsAt.isAfter(now)) {
                    notificationService.showResetNotification(quota.service, window.label)
                }
            }

            prefsManager.saveResetTimes(
                quota.service,
                quota.windows.map { it.label to it.resetsAt }
            )
        }
    }

    companion object {
        
        const val REPO_TIMEOUT_MS = 7_000L
    }
}


@EntryPoint
@InstallIn(SingletonComponent::class)
interface QuotaRefreshEntryPoint {
    fun quotaRefreshService(): QuotaRefreshService
}
