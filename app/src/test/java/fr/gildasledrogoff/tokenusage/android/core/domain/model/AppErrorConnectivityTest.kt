package fr.gildasledrogoff.tokenusage.core.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AppErrorConnectivityTest {
    @Test
    fun `missing dns, refused connection and no route mean no connectivity`() {
        assertTrue(AppError.NetworkError("dns", UnknownHostException("api.example")).isConnectivityFailure)
        assertTrue(AppError.NetworkError("refused", ConnectException()).isConnectivityFailure)
        assertTrue(AppError.NetworkError("route", NoRouteToHostException()).isConnectivityFailure)
    }

    @Test
    fun `a wrapped connectivity cause is found`() {
        val wrapped = IOException("outer", UnknownHostException("api.example"))
        assertTrue(AppError.NetworkError("wrapped", wrapped).isConnectivityFailure)
    }

    @Test
    fun `provider side failures are not connectivity failures`() {
        assertFalse(AppError.NetworkError("HTTP 500").isConnectivityFailure)
        assertFalse(AppError.NetworkError("timeout", SocketTimeoutException()).isConnectivityFailure)
        assertFalse(AppError.ServiceUnavailable.isConnectivityFailure)
        assertFalse(AppError.RateLimited.isConnectivityFailure)
        assertFalse(AppError.AuthError(AiService.CLAUDE, isTerminal = true).isConnectivityFailure)
    }
}
