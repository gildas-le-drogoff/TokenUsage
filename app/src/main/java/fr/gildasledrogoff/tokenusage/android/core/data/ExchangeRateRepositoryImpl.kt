package fr.gildasledrogoff.tokenusage.core.data
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Currencies
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRate
import fr.gildasledrogoff.tokenusage.core.domain.model.FxRateSource
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.domain.repository.ExchangeRateRepository
import fr.gildasledrogoff.tokenusage.core.network.fx.ExchangeRateEndpoints
import fr.gildasledrogoff.tokenusage.core.network.fx.ExchangeRateParsers
import fr.gildasledrogoff.tokenusage.core.security.CachedEurRate
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
class ExchangeRateRepositoryImpl(
    private val client: OkHttpClient,
    private val json: Json,
    private val prefsManager: EncryptedPrefsManager,
    private val frankfurterBaseUrl: String = ExchangeRateEndpoints.FRANKFURTER_BASE_URL,
    private val ecbUrl: String = ExchangeRateEndpoints.ECB_DAILY_URL,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis
) : ExchangeRateRepository {
    private val mutex = Mutex()
    private data class EurQuote(val rate: Double, val date: String?)
    override suspend fun displayRate(currency: String): FxRate? {
        val from = currency.trim().uppercase()
        val to = prefsManager.getDisplayCurrency().code ?: return null
        if (from.isEmpty() || from == to) return null
        return when (val source = prefsManager.getFxRateSource()) {
            FxRateSource.MANUAL -> manualRate(from, to)
            FxRateSource.FRANKFURTER, FxRateSource.ECB -> if (!prefsManager.isFxApiFetchAllowed()) {
                cachedRate(source, from, to)
            } else {
                val fromQuote = eurQuote(source, from) ?: return null
                val toQuote = eurQuote(source, to) ?: return null
                cross(source, from, to, fromQuote, toQuote)
            }
        }
    }
    override suspend fun fetchLatest(source: FxRateSource, from: String, to: String): Result<FxRate, AppError> {
        val fromCode = from.trim().uppercase()
        val toCode = to.trim().uppercase()
        if (fromCode == toCode) return Result.Failure(AppError.ParseError("Nothing to convert: $fromCode"))
        return when (source) {
            FxRateSource.MANUAL -> manualRate(fromCode, toCode)?.let { Result.Success(it) }
                ?: Result.Failure(AppError.ParseError("No manual rate for $fromCode → $toCode"))
            FxRateSource.FRANKFURTER, FxRateSource.ECB -> mutex.withLock {
                val startedAtMs = nowMs()
                val fromQuote = when (val fetched = freshEurQuote(source, fromCode)) {
                    is Result.Success -> fetched.value
                    is Result.Failure -> return@withLock fetched
                }
                val toQuote = quoteCachedSince(source, toCode, startedAtMs)
                    ?: when (val fetched = freshEurQuote(source, toCode)) {
                        is Result.Success -> fetched.value
                        is Result.Failure -> return@withLock fetched
                    }
                Result.Success(cross(source, fromCode, toCode, fromQuote, toQuote))
            }
        }
    }
    override fun cachedRate(source: FxRateSource, from: String, to: String): FxRate? {
        val fromCode = from.trim().uppercase()
        val toCode = to.trim().uppercase()
        if (fromCode == toCode) return null
        if (source == FxRateSource.MANUAL) return manualRate(fromCode, toCode)
        val fromQuote = cachedEurQuote(source, fromCode) ?: return null
        val toQuote = cachedEurQuote(source, toCode) ?: return null
        return cross(source, fromCode, toCode, fromQuote, toQuote)
    }
    private fun cross(source: FxRateSource, from: String, to: String, fromQuote: EurQuote, toQuote: EurQuote) =
        FxRate(
            from = from,
            to = to,
            rate = fromQuote.rate / toQuote.rate,
            source = source,
            date = fromQuote.date ?: toQuote.date
        )
    private fun manualRate(from: String, to: String): FxRate? {
        val fromUsd = manualUsdValue(from) ?: return null
        val toUsd = manualUsdValue(to) ?: return null
        return FxRate(from = from, to = to, rate = fromUsd / toUsd, source = FxRateSource.MANUAL)
    }
    private fun manualUsdValue(code: String): Double? = when (code) {
        Currencies.USD -> 1.0
        Currencies.EUR -> prefsManager.getManualUsdEurRate()?.let { 1.0 / it }
        Currencies.CNY -> prefsManager.getManualUsdCnyRate()?.let { 1.0 / it }
        else -> null
    }
    private fun cachedEurQuote(source: FxRateSource, code: String): EurQuote? {
        if (code == Currencies.EUR) return EurQuote(1.0, null)
        return prefsManager.getCachedEurRate(source, code)?.let { EurQuote(it.rate, it.date) }
    }
    private suspend fun eurQuote(source: FxRateSource, code: String): EurQuote? {
        if (code == Currencies.EUR) return EurQuote(1.0, null)
        return mutex.withLock {
            val cached = prefsManager.getCachedEurRate(source, code)
            if (cached != null && nowMs() - cached.fetchedAtMs in 0 until cacheTtlMs) {
                return@withLock EurQuote(cached.rate, cached.date)
            }
            when (val fetched = fetchAndCache(source, code)) {
                is Result.Success -> fetched.value
                is Result.Failure -> cached?.let { EurQuote(it.rate, it.date) }
            }
        }
    }
    private suspend fun freshEurQuote(source: FxRateSource, code: String): Result<EurQuote, AppError> =
        if (code == Currencies.EUR) Result.Success(EurQuote(1.0, null)) else fetchAndCache(source, code)
    private fun quoteCachedSince(source: FxRateSource, code: String, sinceMs: Long): EurQuote? {
        if (code == Currencies.EUR) return null
        return prefsManager.getCachedEurRate(source, code)
            ?.takeIf { it.fetchedAtMs >= sinceMs }
            ?.let { EurQuote(it.rate, it.date) }
    }
    private suspend fun fetchAndCache(source: FxRateSource, code: String): Result<EurQuote, AppError> =
        when (source) {
            FxRateSource.FRANKFURTER -> fetchFrankfurter(code).also { result ->
                if (result is Result.Success) cache(source, code, result.value, nowMs())
            }
            FxRateSource.ECB -> fetchEcb(code)
            FxRateSource.MANUAL -> Result.Failure(AppError.ParseError("Not a remote source: $source"))
        }
    private fun cache(source: FxRateSource, code: String, quote: EurQuote, fetchedAtMs: Long) {
        prefsManager.saveCachedEurRate(
            source,
            code,
            CachedEurRate(rate = quote.rate, date = quote.date, fetchedAtMs = fetchedAtMs)
        )
    }
    private suspend fun fetchFrankfurter(code: String): Result<EurQuote, AppError> {
        val url = frankfurterBaseUrl.trimEnd('/') + "/" + ExchangeRateEndpoints.frankfurterRatePath(code)
        return when (val body = get(url)) {
            is Result.Failure -> body
            is Result.Success -> ExchangeRateParsers.parseFrankfurter(body.value, code, json)
                ?.let { Result.Success(EurQuote(it.rate, it.date)) }
                ?: Result.Failure(AppError.ParseError("Invalid Frankfurter rate for $code"))
        }
    }
    private suspend fun fetchEcb(code: String): Result<EurQuote, AppError> {
        return when (val body = get(ecbUrl)) {
            is Result.Failure -> body
            is Result.Success -> {
                val (rates, date) = ExchangeRateParsers.parseEcb(body.value)
                    ?: return Result.Failure(AppError.ParseError("Invalid ECB reference rates"))
                val fetchedAtMs = nowMs()
                (Currencies.PROVIDER + code).distinct().forEach { quoted ->
                    rates[quoted]?.let { cache(FxRateSource.ECB, quoted, EurQuote(it, date), fetchedAtMs) }
                }
                rates[code]?.let { Result.Success(EurQuote(it, date)) }
                    ?: Result.Failure(AppError.ParseError("$code is not quoted by the ECB"))
            }
        }
    }
    private suspend fun get(url: String): Result<String, AppError> = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use when (response.code) {
                        429 -> Result.Failure(AppError.RateLimited)
                        503 -> Result.Failure(AppError.ServiceUnavailable)
                        else -> Result.Failure(AppError.NetworkError("HTTP ${response.code}"))
                    }
                }
                val text = response.body.string()
                if (text.length > MAX_BODY_CHARS) {
                    Result.Failure(AppError.ParseError("Exchange-rate response too large"))
                } else {
                    Result.Success(text)
                }
            }
        } catch (e: IOException) {
            Result.Failure(AppError.NetworkError(e.message ?: "Network error", e))
        } catch (e: IllegalArgumentException) {
            Result.Failure(AppError.NetworkError("Invalid URL: $url", e))
        }
    }
    companion object {
        val DEFAULT_CACHE_TTL_MS: Long = TimeUnit.HOURS.toMillis(6)
        private const val MAX_BODY_CHARS = 256 * 1024
    }
}
