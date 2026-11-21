package fr.gildasledrogoff.tokenusage.di
import fr.gildasledrogoff.tokenusage.core.demo.SampleDataRepository
import fr.gildasledrogoff.tokenusage.core.domain.repository.DemoDataRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
@Module
@InstallIn(SingletonComponent::class)
abstract class DemoDataModule {
    @Binds
    abstract fun bindDemoDataRepository(impl: SampleDataRepository): DemoDataRepository
}
