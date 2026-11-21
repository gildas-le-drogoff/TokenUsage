package fr.gildasledrogoff.tokenusage.di
import fr.gildasledrogoff.tokenusage.core.diagnostics.BackgroundStateReader
import fr.gildasledrogoff.tokenusage.core.diagnostics.BackgroundStateSource
import fr.gildasledrogoff.tokenusage.core.diagnostics.ConfiguredServicesSource
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {
    @Binds
    abstract fun bindBackgroundState(impl: BackgroundStateReader): BackgroundStateSource
    companion object {
        @Provides
        fun provideConfiguredServices(prefs: EncryptedPrefsManager): ConfiguredServicesSource =
            ConfiguredServicesSource { service -> prefs.hasCredential(service) }
    }
}
