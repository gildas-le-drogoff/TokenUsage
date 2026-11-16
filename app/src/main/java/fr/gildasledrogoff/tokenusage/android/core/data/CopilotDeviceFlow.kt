package fr.gildasledrogoff.tokenusage.core.data

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.network.copilot.CopilotDto
import fr.gildasledrogoff.tokenusage.core.network.copilot.GitHubDeviceFlowService
import kotlinx.coroutines.delay
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class CopilotDeviceFlow @Inject constructor(
    private val service: GitHubDeviceFlowService
) {

    suspend fun start(): Result<CopilotDto.DeviceCodeResponse, AppError> = try {
        val response = service.requestDeviceCode()
        val body = response.body()
        if (response.isSuccessful && body != null && body.deviceCode.isNotBlank() && body.userCode.isNotBlank()) {
            Result.Success(body)
        } else {
            Result.Failure(AppError.NetworkError("HTTP ${response.code()}"))
        }
    } catch (e: IOException) {
        Result.Failure(AppError.NetworkError(e.message ?: "Network error", e))
    }

    
    suspend fun awaitToken(code: CopilotDto.DeviceCodeResponse): Result<String, AppError> {
        var intervalMs = code.interval.coerceAtLeast(1) * 1000L
        val deadline = System.currentTimeMillis() + code.expiresIn.coerceAtLeast(60) * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            val response = try {
                service.pollAccessToken(deviceCode = code.deviceCode)
            } catch (e: IOException) {
                null
            } ?: continue
            if (!response.isSuccessful) {
                return Result.Failure(AppError.NetworkError("HTTP ${response.code()}"))
            }
            val body = response.body() ?: continue

            body.accessToken?.takeIf { it.isNotBlank() }?.let { return Result.Success(it) }
            when (body.error) {
                CopilotDto.ERROR_PENDING, null -> Unit
                CopilotDto.ERROR_SLOW_DOWN -> intervalMs += SLOW_DOWN_STEP_MS
                CopilotDto.ERROR_DENIED ->
                    return Result.Failure(AppError.AuthError(AiService.COPILOT, isTerminal = true, message = "denied"))
                CopilotDto.ERROR_EXPIRED -> return expired()
                else -> return Result.Failure(
                    AppError.AuthError(AiService.COPILOT, isTerminal = true, message = body.error)
                )
            }
        }
        return expired()
    }

    private fun expired(): Result<String, AppError> =
        Result.Failure(AppError.AuthError(AiService.COPILOT, isTerminal = true, message = "expired"))

    private companion object {
        
        const val SLOW_DOWN_STEP_MS = 5_000L
    }
}
