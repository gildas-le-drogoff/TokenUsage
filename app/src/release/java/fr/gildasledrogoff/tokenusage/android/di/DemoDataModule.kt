package fr.gildasledrogoff.tokenusage.di
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.BalanceSample
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionGranularity
import fr.gildasledrogoff.tokenusage.core.domain.model.ConsumptionPoint
import fr.gildasledrogoff.tokenusage.core.domain.model.DeepseekAccount
import fr.gildasledrogoff.tokenusage.core.domain.repository.BalanceHistoryRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoQuota
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
@Module
@InstallIn(SingletonComponent::class)
object DemoDataModule {
    @Provides
    @Singleton
    fun provideDemoDataRepository(): DemoDataRepository = NoDemoDataRepository
}
private object NoDemoDataRepository : DemoDataRepository {
    override val isAvailable: Boolean = false
    override val supportedServices: Set<AiService> = emptySet()
    override fun isEnabled(): Boolean = false
    override fun setEnabled(enabled: Boolean) = Unit
    override suspend fun sampleQuotas(): List<DemoQuota> = emptyList()
    override fun sampleDeepseekAccounts(): List<DeepseekAccount> = emptyList()
    override val history: BalanceHistoryRepository = object : BalanceHistoryRepository {
        override suspend fun record(
            service: AiService,
            accountId: String,
            accountLabel: String,
            currency: String,
            totalBalance: Double
        ) = Unit
        override suspend fun latestSample(service: AiService, accountId: String): BalanceSample? = null
        override suspend fun consumption(
            service: AiService,
            accountId: String,
            granularity: ConsumptionGranularity
        ): List<ConsumptionPoint> = emptyList()
    }
}
