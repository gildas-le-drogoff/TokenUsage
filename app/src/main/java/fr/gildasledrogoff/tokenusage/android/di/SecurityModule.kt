package fr.gildasledrogoff.tokenusage.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.security.SecureStorageFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {
    @Provides
    @Singleton
    fun provideEncryptedPrefsManager(
        storageFactory: SecureStorageFactory
    ): EncryptedPrefsManager = EncryptedPrefsManager(storageFactory)
}
