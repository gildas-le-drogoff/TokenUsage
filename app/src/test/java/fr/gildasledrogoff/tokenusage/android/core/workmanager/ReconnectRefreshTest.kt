package fr.gildasledrogoff.tokenusage.core.workmanager

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import fr.gildasledrogoff.tokenusage.core.diagnostics.RefreshOrigin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class ReconnectRefreshTest {
    private lateinit var context: Context
    private lateinit var reconnect: WorkManagerReconnectRefresh

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("tokenusage_reconnect_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        reconnect = WorkManagerReconnectRefresh(context)
    }

    private fun pending(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerReconnectRefresh.WORK_NAME).get()


    @Test
    fun `reconnect refresh is disabled by default`() {
        assertFalse(reconnect.isEnabled())
    }

    @Test
    fun `setEnabled persists the flag`() {
        reconnect.setEnabled(true)
        assertTrue(reconnect.isEnabled())
        assertTrue(WorkManagerReconnectRefresh(context).isEnabled())

        reconnect.setEnabled(false)
        assertFalse(WorkManagerReconnectRefresh(context).isEnabled())
    }

    @Test
    fun `onOffline does nothing while disabled`() {
        reconnect.onOffline()

        assertTrue(pending().isEmpty())
    }

    @Test
    fun `onOffline enqueues a connected one-time work when enabled`() {
        reconnect.setEnabled(true)

        reconnect.onOffline()

        val info = pending().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertTrue(WorkManagerReconnectRefresh.WORK_NAME in info.tags)
    }

    @Test
    fun `repeated offline signals keep a single pending work`() {
        reconnect.setEnabled(true)

        reconnect.onOffline()
        reconnect.onOffline()
        reconnect.onOffline()

        assertEquals(1, pending().size)
    }

    @Test
    fun `onOnline cancels the pending work`() {
        reconnect.setEnabled(true)
        reconnect.onOffline()

        reconnect.onOnline()

        assertEquals(WorkInfo.State.CANCELLED, pending().single().state)
    }

    @Test
    fun `disabling the feature cancels the pending work`() {
        reconnect.setEnabled(true)
        reconnect.onOffline()

        reconnect.setEnabled(false)

        assertEquals(WorkInfo.State.CANCELLED, pending().single().state)
    }


    private fun worker(
        outcome: QuotaRefreshService.Outcome,
        attempt: Int
    ): ReconnectRefreshWorker {
        val service = mock(QuotaRefreshService::class.java)
        kotlinx.coroutines.runBlocking {
            `when`(service.refreshNow(RefreshOrigin.RECONNECT)).thenReturn(outcome)
        }
        return TestListenableWorkerBuilder<ReconnectRefreshWorker>(context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker =
                    ReconnectRefreshWorker(appContext, workerParameters, service)
            })
            .build()
    }

    private val offline = QuotaRefreshService.Outcome(emptyList(), anyServiceConfigured = true, offline = true)
    private val online = QuotaRefreshService.Outcome(emptyList(), anyServiceConfigured = true, offline = false)

    @Test
    fun `worker retries while still offline within the attempt budget`() = runTest {
        assertEquals(ListenableWorker.Result.retry(), worker(offline, attempt = 0).doWork())
        assertEquals(ListenableWorker.Result.retry(), worker(offline, attempt = 2).doWork())
    }

    @Test
    fun `worker gives up once the attempt budget is spent`() = runTest {
        assertEquals(ListenableWorker.Result.success(), worker(offline, attempt = 3).doWork())
    }

    @Test
    fun `worker succeeds when the refresh reached the network`() = runTest {
        assertEquals(ListenableWorker.Result.success(), worker(online, attempt = 0).doWork())
    }
}
