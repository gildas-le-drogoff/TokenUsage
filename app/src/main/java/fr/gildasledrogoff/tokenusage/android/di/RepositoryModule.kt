package fr.gildasledrogoff.tokenusage.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import fr.gildasledrogoff.tokenusage.core.data.BalanceHistoryRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.DisplayPreferencesRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.ExchangeRateRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.data.LastFetchRepositoryImpl
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.LastFetchRepository
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
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
    fun provideLastFetchRepository(
        lastFetchRepository: LastFetchRepositoryImpl
    ): LastFetchRepository = lastFetchRepository

    @Provides
    @Singleton
    fun provideExchangeRateRepository(
        @ExchangeRateClient client: OkHttpClient,
        json: Json,
        prefsManager: EncryptedPrefsManager
    ): ExchangeRateRepository = ExchangeRateRepositoryImpl(client, json, prefsManager)
}
