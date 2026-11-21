package fr.gildasledrogoff.tokenusage.core.domain.model

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

sealed class AppError {
    data class NetworkError(val message: String, val cause: Throwable? = null) : AppError()
    data class AuthError(
        val service: AiService,
        val isTerminal: Boolean,
        val message: String = ""
    ) : AppError()

    data object RateLimited : AppError()
    data class ParseError(val message: String, val cause: Throwable? = null) : AppError()
    data class CredentialNotFound(val service: AiService) : AppError()
    data class TokenRevoked(val service: AiService) : AppError()
    data object ServiceUnavailable : AppError()
    data class ClientUnsupported(val service: AiService) : AppError()
}

val AppError.isConnectivityFailure: Boolean
    get() = this is AppError.NetworkError &&
        generateSequence(cause) { it.cause }.take(MAX_CAUSE_DEPTH).any {
            it is UnknownHostException || it is ConnectException || it is NoRouteToHostException
        }

private const val MAX_CAUSE_DEPTH = 8
