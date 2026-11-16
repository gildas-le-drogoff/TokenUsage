package fr.gildasledrogoff.tokenusage.di

import fr.gildasledrogoff.tokenusage.core.data.BalanceHistoryRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.ClaudeRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.CodexRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.CopilotRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.DisplayPreferencesRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.DeepseekRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.ExchangeRateRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.GeminiRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.OpenAiRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.OpenRouterRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.ZaiRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ClaudeQuotaRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.MultiAccountQuotaRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.QuotaRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ZaiRepository
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeApiService
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeWebApiService
import fr.gildasledrogoff.tokenusage.core.network.codex.CodexApiService
import fr.gildasledrogoff.tokenusage.core.network.codex.CodexTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.network.copilot.CopilotApiService
import fr.gildasledrogoff.tokenusage.core.network.deepseek.DeepseekApiService
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiApiService
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiProjectsService
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.widget.WidgetPrefsManager
import fr.gildasledrogoff.tokenusage.core.widget.WidgetQuotaPublisher
import fr.gildasledrogoff.tokenusage.core.network.openai.OpenAiApiService
import fr.gildasledrogoff.tokenusage.core.network.openrouter.OpenRouterApiService
import fr.gildasledrogoff.tokenusage.core.network.zai.ZaiApiService
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ClaudeRepository

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CodexRepository

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GeminiRepository

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeepseekRepository

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ZaiRepositoryQualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CopilotRepository

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OpenRouterRepository

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OpenAiRepository

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    @ClaudeRepository
    fun provideClaudeRepository(
        apiService: ClaudeApiService,
        webApiService: ClaudeWebApiService,
        tokenRefreshService: ClaudeTokenRefreshService,
        prefsManager: EncryptedPrefsManager
    ): ClaudeQuotaRepository =
        ClaudeRepositoryImpl(apiService, webApiService, tokenRefreshService, prefsManager)

    @Provides
    @Singleton
    @CodexRepository
    fun provideCodexRepository(
        apiService: CodexApiService,
        tokenRefreshService: CodexTokenRefreshService,
        prefsManager: EncryptedPrefsManager,
        widgetPrefs: WidgetPrefsManager,
        widgetQuotaPublisher: WidgetQuotaPublisher
    ): QuotaRepository = CodexRepositoryImpl(
        apiService, tokenRefreshService, prefsManager, widgetPrefs, widgetQuotaPublisher
    )

    @Provides
    @Singleton
    @GeminiRepository
    fun provideGeminiRepository(
        apiService: GeminiApiService,
        projectsService: GeminiProjectsService,
        tokenRefreshService: GeminiTokenRefreshService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = GeminiRepositoryImpl(apiService, projectsService, tokenRefreshService, prefsManager)

    @Provides
    @Singleton
    @DeepseekRepository
    fun provideDeepseekRepository(
        apiService: DeepseekApiService,
        prefsManager: EncryptedPrefsManager,
        balanceHistoryRepository: BalanceHistoryRepository
    ): MultiAccountQuotaRepository = DeepseekRepositoryImpl(apiService, prefsManager, balanceHistoryRepository)

    @Provides
    @Singleton
    @ZaiRepositoryQualifier
    fun provideZaiRepository(
        apiService: ZaiApiService,
        prefsManager: EncryptedPrefsManager
    ): ZaiRepository = ZaiRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @CopilotRepository
    fun provideCopilotRepository(
        apiService: CopilotApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = CopilotRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @OpenRouterRepository
    fun provideOpenRouterRepository(
        apiService: OpenRouterApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = OpenRouterRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @OpenAiRepository
    fun provideOpenAiRepository(
        apiService: OpenAiApiService,
        prefsManager: EncryptedPrefsManager,
        clock: Clock
    ): QuotaRepository = OpenAiRepositoryImpl(apiService, prefsManager, clock)

    @Provides
    @Singleton
    fun provideDisplayPreferencesRepository(
        impl: DisplayPreferencesRepositoryImpl
    ): DisplayPreferencesRepository = impl

    @Provides
    @Singleton
    fun provideBalanceHistoryRepository(
        balanceHistoryRepository: BalanceHistoryRepositoryImpl
    ): BalanceHistoryRepository = balanceHistoryRepository

    @Provides
    @Singleton
    fun provideExchangeRateRepository(
        @ExchangeRateClient client: OkHttpClient,
        json: Json,
        prefsManager: EncryptedPrefsManager
    ): ExchangeRateRepository = ExchangeRateRepositoryImpl(client, json, prefsManager)
}
