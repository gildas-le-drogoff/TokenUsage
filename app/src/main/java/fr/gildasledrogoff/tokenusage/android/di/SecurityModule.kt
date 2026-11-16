package fr.gildasledrogoff.tokenusage.di

import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import fr.gildasledrogoff.tokenusage.core.security.SecureStorageFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
