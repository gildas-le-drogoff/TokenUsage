package fr.gildasledrogoff.tokenusage.core.diagnostics

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import kotlinx.serialization.Serializable

enum class DiagnosticStatus { OK, ERROR, NOT_CONFIGURED, NEVER_REFRESHED }

@Serializable
data class DiagnosticError(
    val type: String,
    val message: String = ""
) {
    companion object {
        fun from(error: AppError): DiagnosticError {
            val (type, message) = when (error) {
                is AppError.NetworkError -> "NetworkError" to error.message
                is AppError.AuthError ->
                    "AuthError" to buildString {
                        append(if (error.isTerminal) "terminal" else "transient")
                        if (error.message.isNotBlank()) append(": ").append(error.message)
                    }

                AppError.RateLimited -> "RateLimited" to ""
                is AppError.ParseError -> "ParseError" to error.message
                is AppError.CredentialNotFound -> "CredentialNotFound" to ""
                is AppError.TokenRevoked -> "TokenRevoked" to ""
                AppError.ServiceUnavailable -> "ServiceUnavailable" to ""
                is AppError.ClientUnsupported -> "ClientUnsupported" to ""
            }
            return DiagnosticError(type, DiagnosticsSanitizer.sanitize(message))
        }
    }
}

@Serializable
data class DiagnosticRecord(
    val accountKey: String,
    val service: AiService,
    val accountLabel: String? = null,
    val status: DiagnosticStatus = DiagnosticStatus.OK,
    val atEpochMs: Long,
    val origin: RefreshOrigin = RefreshOrigin.MANUAL_SERVICE,
    val error: DiagnosticError? = null,

    val lastError: DiagnosticError? = null,
    val lastErrorAtEpochMs: Long? = null
) {
    companion object {
        fun keyOf(service: AiService, accountNumber: Int?): String =
            "${service.name}:${accountNumber ?: "default"}"
    }
}

@Serializable
data class WorkerRun(
    val workName: String,
    val atEpochMs: Long,

    val result: String,
    val attempt: Int = 0
)
