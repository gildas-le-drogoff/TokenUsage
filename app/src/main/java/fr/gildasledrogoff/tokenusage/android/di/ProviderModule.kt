package fr.gildasledrogoff.tokenusage.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import fr.gildasledrogoff.tokenusage.core.data.ClaudeRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.CodexRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.CopilotRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.DeepseekRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.GeminiRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.OpenAiRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.OpenRouterRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.ZaiRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.provider.QuotaProvider

/**
 * One line per service: this is where a provider joins the `ProviderRegistry`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderModule {

    @Binds @IntoSet abstract fun claude(impl: ClaudeRepositoryImpl): QuotaProvider
    @Binds @IntoSet abstract fun codex(impl: CodexRepositoryImpl): QuotaProvider
    @Binds @IntoSet abstract fun gemini(impl: GeminiRepositoryImpl): QuotaProvider
    @Binds @IntoSet abstract fun deepseek(impl: DeepseekRepositoryImpl): QuotaProvider
    @Binds @IntoSet abstract fun zai(impl: ZaiRepositoryImpl): QuotaProvider
    @Binds @IntoSet abstract fun copilot(impl: CopilotRepositoryImpl): QuotaProvider
    @Binds @IntoSet abstract fun openRouter(impl: OpenRouterRepositoryImpl): QuotaProvider
    @Binds @IntoSet abstract fun openAi(impl: OpenAiRepositoryImpl): QuotaProvider
}
