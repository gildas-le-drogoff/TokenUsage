package fr.gildasledrogoff.tokenusage.di

import fr.gildasledrogoff.tokenusage.core.alert.AlertPreferencesStore
import fr.gildasledrogoff.tokenusage.core.alert.BalanceSeriesSource
import fr.gildasledrogoff.tokenusage.core.alert.DaoBalanceSeriesSource
import fr.gildasledrogoff.tokenusage.core.security.SecureStorageFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AlertModule {

    @Binds
    abstract fun bindBalanceSeriesSource(impl: DaoBalanceSeriesSource): BalanceSeriesSource

    companion object {
        @Provides
        @Singleton
        fun provideAlertPreferencesStore(storageFactory: SecureStorageFactory): AlertPreferencesStore =
            AlertPreferencesStore { storageFactory.credentials }
    }
}
