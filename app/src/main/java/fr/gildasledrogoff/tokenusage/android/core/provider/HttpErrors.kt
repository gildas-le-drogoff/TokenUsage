package fr.gildasledrogoff.tokenusage.core.provider

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** HTTP status → [AppError], the mapping shared by every provider. */
fun httpError(
    service: AiService,
    code: Int,
    authCodes: Set<Int> = setOf(401),
    unavailableCodes: Set<Int> = setOf(503),
    authIsTerminal: Boolean = true,
    authMessage: String = ""
): AppError = when (code) {
    in authCodes -> AppError.AuthError(service, isTerminal = authIsTerminal, message = authMessage)
    429 -> AppError.RateLimited
    in unavailableCodes -> AppError.ServiceUnavailable
    else -> AppError.NetworkError("HTTP $code")
}

/** Runs [block], mapping thrown I/O and parsing failures to [AppError]. */
inline fun <T> guardedFetch(block: () -> Result<T, AppError>): Result<T, AppError> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    Result.Failure(AppError.NetworkError(e.message ?: "Network error", e))
} catch (e: Exception) {
    Result.Failure(AppError.ParseError("${e::class.simpleName}: ${e.message}", e))
}
