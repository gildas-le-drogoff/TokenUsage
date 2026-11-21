package fr.gildasledrogoff.tokenusage.core.network

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import app.cash.turbine.test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class AndroidNetworkMonitorTest {
    private lateinit var context: Context
    private lateinit var manager: ConnectivityManager
    private lateinit var network: Network

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        manager = mock(ConnectivityManager::class.java)
        network = mock(Network::class.java)
        `when`(context.getSystemService(ConnectivityManager::class.java)).thenReturn(manager)
        `when`(manager.activeNetwork).thenReturn(network)
    }

    private fun capabilities(internet: Boolean): NetworkCapabilities {
        val caps = mock(NetworkCapabilities::class.java)
        `when`(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(internet)
        return caps
    }

    private fun online(internet: Boolean) {
        val caps = capabilities(internet)
        `when`(manager.getNetworkCapabilities(network)).thenReturn(caps)
    }


    @Test
    fun `default onlineChanges of a functional monitor is empty`() = runTest {
        val monitor = NetworkMonitor { true }

        assertTrue(monitor.isOnline())
        assertEquals(emptyList<Boolean>(), monitor.onlineChanges().toList())
    }


    @Test
    fun `isOnline is true with internet capability`() {
        online(true)

        assertTrue(AndroidNetworkMonitor(context).isOnline())
    }

    @Test
    fun `isOnline is false without internet capability`() {
        online(false)

        assertFalse(AndroidNetworkMonitor(context).isOnline())
    }

    @Test
    fun `isOnline is false when there is no active network capabilities`() {
        `when`(manager.getNetworkCapabilities(network)).thenReturn(null)

        assertFalse(AndroidNetworkMonitor(context).isOnline())
    }

    @Test
    fun `isOnline is optimistic when the connectivity manager is unavailable`() {
        `when`(context.getSystemService(ConnectivityManager::class.java)).thenReturn(null)

        assertTrue(AndroidNetworkMonitor(context).isOnline())
    }

    @Test
    fun `isOnline is optimistic when the platform call throws`() {
        `when`(manager.getNetworkCapabilities(network)).thenThrow(SecurityException("denied"))

        assertTrue(AndroidNetworkMonitor(context).isOnline())
    }


    @Test
    fun `onlineChanges completes immediately without a connectivity manager`() = runTest {
        `when`(context.getSystemService(ConnectivityManager::class.java)).thenReturn(null)

        AndroidNetworkMonitor(context).onlineChanges().test {
            awaitComplete()
        }
    }

    @Test
    fun `onlineChanges emits the current state then follows the callback`() = runTest {
        online(true)
        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)

        AndroidNetworkMonitor(context).onlineChanges().test {
            assertTrue(awaitItem())
            verify(manager).registerDefaultNetworkCallback(captor.capture())
            val callback = captor.value

            callback.onCapabilitiesChanged(network, capabilities(false))
            assertFalse(awaitItem())

            callback.onCapabilitiesChanged(network, capabilities(true))
            assertTrue(awaitItem())

            online(false)
            callback.onLost(network)
            assertFalse(awaitItem())

            online(true)
            callback.onAvailable(network)
            assertTrue(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onlineChanges drops repeated identical states`() = runTest {
        online(true)
        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)

        AndroidNetworkMonitor(context).onlineChanges().test {
            assertTrue(awaitItem())
            verify(manager).registerDefaultNetworkCallback(captor.capture())

            captor.value.onCapabilitiesChanged(network, capabilities(true))
            captor.value.onCapabilitiesChanged(network, capabilities(false))
            assertFalse(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onlineChanges unregisters the callback when collection stops`() = runTest {
        online(true)
        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)

        AndroidNetworkMonitor(context).onlineChanges().test {
            awaitItem()
            verify(manager).registerDefaultNetworkCallback(captor.capture())
            cancelAndIgnoreRemainingEvents()
        }

        verify(manager).unregisterNetworkCallback(captor.value)
    }

    @Test
    fun `onlineChanges tolerates a failing registration`() = runTest {
        online(true)
        `when`(manager.registerDefaultNetworkCallback(org.mockito.ArgumentMatchers.any(ConnectivityManager.NetworkCallback::class.java)))
            .thenThrow(SecurityException("denied"))

        AndroidNetworkMonitor(context).onlineChanges().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        org.mockito.Mockito.verify(manager, org.mockito.Mockito.never())
            .unregisterNetworkCallback(org.mockito.ArgumentMatchers.any(ConnectivityManager.NetworkCallback::class.java))
    }
}
