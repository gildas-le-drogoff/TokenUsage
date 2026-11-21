package fr.gildasledrogoff.tokenusage.core.data
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.Result
import fr.gildasledrogoff.tokenusage.core.network.copilot.CopilotDto
import fr.gildasledrogoff.tokenusage.core.network.copilot.GitHubDeviceFlowService
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import retrofit2.Response
class CopilotDeviceFlowTest {
    private val code = CopilotDto.DeviceCodeResponse(
        deviceCode = "device-code-1",
        userCode = "ABCD-1234",
        interval = 1
    )
    @Test
    fun `poll returns the token on success`() = runTest {
        val service = mock(GitHubDeviceFlowService::class.java)
        `when`(service.pollAccessToken(deviceCode = code.deviceCode))
            .thenReturn(Response.success(CopilotDto.AccessTokenResponse(accessToken = "gho-token")))
        val flow = CopilotDeviceFlow(service)
        val result = flow.awaitToken(code)
        assertEquals("gho-token", (result as Result.Success).value)
    }
    @Test
    fun `http error during polling fails immediately instead of looping until expiry`() = runTest {
        val service = mock(GitHubDeviceFlowService::class.java)
        `when`(service.pollAccessToken(deviceCode = code.deviceCode))
            .thenReturn(Response.error(503, "".toResponseBody(null)))
        val flow = CopilotDeviceFlow(service)
        val result = flow.awaitToken(code)
        val error = (result as Result.Failure).error
        assertEquals("HTTP 503", (error as AppError.NetworkError).message)
        verify(service, times(1)).pollAccessToken(deviceCode = code.deviceCode)
    }
    @Test
    fun `denied access is a terminal auth error`() = runTest {
        val service = mock(GitHubDeviceFlowService::class.java)
        `when`(service.pollAccessToken(deviceCode = code.deviceCode))
            .thenReturn(Response.success(CopilotDto.AccessTokenResponse(error = CopilotDto.ERROR_DENIED)))
        val flow = CopilotDeviceFlow(service)
        val result = flow.awaitToken(code)
        val error = (result as Result.Failure).error
        assertTrue(error is AppError.AuthError && error.isTerminal)
    }
}
