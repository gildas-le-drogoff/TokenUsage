package fr.gildasledrogoff.tokenusage.di
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import fr.gildasledrogoff.tokenusage.BuildConfig
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.network.RetryInterceptor
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeApiService
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.network.claude.ClaudeWebApiService
import fr.gildasledrogoff.tokenusage.core.network.codex.CodexApiService
import fr.gildasledrogoff.tokenusage.core.network.codex.CodexTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.network.copilot.CopilotApiService
import fr.gildasledrogoff.tokenusage.core.network.copilot.GitHubDeviceFlowService
import fr.gildasledrogoff.tokenusage.core.network.deepseek.DeepseekApiService
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiApiService
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiProjectsService
import fr.gildasledrogoff.tokenusage.core.network.gemini.GeminiTokenRefreshService
import fr.gildasledrogoff.tokenusage.core.network.openai.OpenAiApiService
import fr.gildasledrogoff.tokenusage.core.network.openrouter.OpenRouterApiService
import fr.gildasledrogoff.tokenusage.core.network.zai.ZaiApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ClaudeClient
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ClaudeWebClient
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CodexClient
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GeminiClient
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeepseekClient
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ZaiClient
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CopilotClient
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OpenRouterClient
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OpenAiClient
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ExchangeRateClient
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ClaudeTokenClient
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CodexTokenClient
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GeminiTokenClient
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }
    private fun baseOkHttpBuilder(): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor())
        if (BuildConfig.IS_DEBUG) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
                redactHeader("Authorization")
                redactHeader("Cookie")
                redactHeader("Set-Cookie")
            }
            builder.addInterceptor(logging)
        }
        return builder
    }
    @Provides
    @Singleton
    @ClaudeClient
    fun provideClaudeOkHttpClient(): OkHttpClient = baseOkHttpBuilder().build()
    @Provides
    @Singleton
    fun provideClaudeApiService(
        @ClaudeClient client: OkHttpClient,
        json: Json
    ): ClaudeApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.CLAUDE.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ClaudeApiService::class.java)
    }
    @Provides
    @Singleton
    @ClaudeTokenClient
    fun provideClaudeTokenOkHttpClient(): OkHttpClient = baseOkHttpBuilder().build()
    @Provides
    @Singleton
    fun provideClaudeTokenRefreshService(
        @ClaudeTokenClient client: OkHttpClient,
        json: Json
    ): ClaudeTokenRefreshService {
        return Retrofit.Builder()
            .baseUrl(ClaudeTokenRefreshService.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ClaudeTokenRefreshService::class.java)
    }
    @Provides
    @Singleton
    @ClaudeWebClient
    fun provideClaudeWebOkHttpClient(): OkHttpClient = baseOkHttpBuilder().build()
    @Provides
    @Singleton
    fun provideClaudeWebApiService(
        @ClaudeWebClient client: OkHttpClient,
        json: Json
    ): ClaudeWebApiService {
        return Retrofit.Builder()
            .baseUrl(ClaudeWebApiService.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ClaudeWebApiService::class.java)
    }
    @Provides
    @Singleton
    @CodexClient
    fun provideCodexOkHttpClient(): OkHttpClient = baseOkHttpBuilder().build()
    @Provides
    @Singleton
    fun provideCodexApiService(
        @CodexClient client: OkHttpClient,
        json: Json
    ): CodexApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.CODEX.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CodexApiService::class.java)
    }
    @Provides
    @Singleton
    @CodexTokenClient
    fun provideCodexTokenOkHttpClient(): OkHttpClient = baseOkHttpBuilder().build()
    @Provides
    @Singleton
    fun provideCodexTokenRefreshService(
        @CodexTokenClient client: OkHttpClient,
        json: Json
    ): CodexTokenRefreshService {
        return Retrofit.Builder()
            .baseUrl(CodexTokenRefreshService.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CodexTokenRefreshService::class.java)
    }
    @Provides
    @Singleton
    @GeminiClient
    fun provideGeminiOkHttpClient(): OkHttpClient = baseOkHttpBuilder().build()
    @Provides
    @Singleton
    fun provideGeminiApiService(
        @GeminiClient client: OkHttpClient,
        json: Json
    ): GeminiApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.GEMINI.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeminiApiService::class.java)
    }
    @Provides
    @Singleton
    @GeminiTokenClient
    fun provideGeminiTokenOkHttpClient(): OkHttpClient = baseOkHttpBuilder().build()
    @Provides
    @Singleton
    fun provideGeminiTokenRefreshService(
        @GeminiTokenClient client: OkHttpClient,
        json: Json
    ): GeminiTokenRefreshService {
        return Retrofit.Builder()
            .baseUrl(GeminiTokenRefreshService.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeminiTokenRefreshService::class.java)
    }
    @Provides
    @Singleton
    fun provideGeminiProjectsService(
        @GeminiClient client: OkHttpClient,
        json: Json
    ): GeminiProjectsService {
        return Retrofit.Builder()
            .baseUrl(GeminiProjectsService.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeminiProjectsService::class.java)
    }
    @Provides
    @Singleton
    @DeepseekClient
    fun provideDeepseekOkHttpClient(): OkHttpClient = baseOkHttpBuilder().build()
    @Provides
    @Singleton
    fun provideDeepseekApiService(
        @DeepseekClient client: OkHttpClient,
        json: Json
    ): DeepseekApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.DEEPSEEK.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DeepseekApiService::class.java)
    }
    @Provides
    @Singleton
    @ZaiClient
    fun provideZaiOkHttpClient(): OkHttpClient = baseOkHttpBuilder().build()
    @Provides
    @Singleton
    fun provideZaiApiService(
        @ZaiClient client: OkHttpClient,
        json: Json
    ): ZaiApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.ZAI.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ZaiApiService::class.java)
    }
    @Provides
    @Singleton
    @CopilotClient
    fun provideCopilotOkHttpClient(): OkHttpClient = baseOkHttpBuilder().build()
    @Provides
    @Singleton
    fun provideCopilotApiService(
        @CopilotClient client: OkHttpClient,
        json: Json
    ): CopilotApiService = retrofit(AiService.COPILOT.baseUrl, client, json)
        .create(CopilotApiService::class.java)
    @Provides
    @Singleton
    fun provideGitHubDeviceFlowService(
        @CopilotClient client: OkHttpClient,
        json: Json
    ): GitHubDeviceFlowService = retrofit(GitHubDeviceFlowService.BASE_URL, client, json)
        .create(GitHubDeviceFlowService::class.java)
    @Provides
    @Singleton
    @OpenRouterClient
    fun provideOpenRouterOkHttpClient(): OkHttpClient = baseOkHttpBuilder().build()
    @Provides
    @Singleton
    fun provideOpenRouterApiService(
        @OpenRouterClient client: OkHttpClient,
        json: Json
    ): OpenRouterApiService = retrofit(AiService.OPENROUTER.baseUrl, client, json)
        .create(OpenRouterApiService::class.java)
    @Provides
    @Singleton
    @OpenAiClient
    fun provideOpenAiOkHttpClient(): OkHttpClient = baseOkHttpBuilder().build()
    @Provides
    @Singleton
    fun provideOpenAiApiService(
        @OpenAiClient client: OkHttpClient,
        json: Json
    ): OpenAiApiService = retrofit(AiService.OPENAI.baseUrl, client, json)
        .create(OpenAiApiService::class.java)
    private fun retrofit(baseUrl: String, client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    @Provides
    @Singleton
    @ExchangeRateClient
    fun provideExchangeRateOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
        if (BuildConfig.IS_DEBUG) {
            builder.addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
        return builder.build()
    }
}
