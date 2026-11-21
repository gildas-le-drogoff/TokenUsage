package fr.gildasledrogoff.tokenusage.core.domain.repository

import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.Result

interface ExchangeRateRepository {
    suspend fun displayRate(currency: String): FxRate?
    suspend fun fetchLatest(
        source: FxRateSource,
        from: String,
        to: String
    ): Result<FxRate, AppError>

    fun cachedRate(source: FxRateSource, from: String, to: String): FxRate?
}
