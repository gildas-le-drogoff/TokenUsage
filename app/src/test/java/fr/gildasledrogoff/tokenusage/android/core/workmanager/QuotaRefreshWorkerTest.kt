package fr.gildasledrogoff.tokenusage.core.workmanager

import android.app.Application
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import fr.gildasledrogoff.tokenusage.core.diagnostics.DiagnosticsStore
import fr.gildasledrogoff.tokenusage.core.diagnostics.RefreshOrigin
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.notification.QuotaNotificationService
import fr.gildasledrogoff.tokenusage.core.security.InMemorySharedPreferences
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class QuotaRefreshWorkerTest {
    private lateinit var context: Context
    private lateinit var diagnostics: DiagnosticsStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        diagnostics = DiagnosticsStore(lazy { InMemorySharedPreferences() }, Clock.systemUTC())
    }

    private fun worker(outcome: QuotaRefreshService.Outcome, attempt: Int = 0): QuotaRefreshWorker {
        val service = mock(QuotaRefreshService::class.java)
        runBlocking {
            `when`(service.refreshNow(RefreshOrigin.PERIODIC_WORKER)).thenReturn(outcome)
        }
        return TestListenableWorkerBuilder<QuotaRefreshWorker>(context)
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker =
                    QuotaRefreshWorker(appContext, workerParameters, service, diagnostics)
            })
            .build()
    }

    private fun outcome(configured: Boolean, quotas: List<QuotaInfo> = emptyList()) =
        QuotaRefreshService.Outcome(quotas, anyServiceConfigured = configured)

    @Test
    fun `nothing configured counts as a success`() = runTest {
        assertEquals(ListenableWorker.Result.success(), worker(outcome(configured = false)).doWork())

        val run = diagnostics.workerRuns().single()
        assertEquals(WorkManagerInitializer.QUOTA_WORK_NAME, run.workName)
        assertEquals(WorkRetryPolicy.Decision.SUCCESS.label, run.result)
    }

    @Test
    fun `configured services without any quota are retried`() = runTest {
        assertEquals(
            ListenableWorker.Result.retry(),
            worker(outcome(configured = true), attempt = 1).doWork()
        )

        val run = diagnostics.workerRuns().single()
        assertEquals(WorkRetryPolicy.Decision.RETRY.label, run.result)
        assertEquals(1, run.attempt)
    }

    @Test
    fun `retries stop at the attempt budget and the work finishes as a success`() = runTest {
        val last = WorkRetryPolicy.MAX_ATTEMPTS - 1
        assertEquals(
            ListenableWorker.Result.success(),
            worker(outcome(configured = true), attempt = last).doWork()
        )

        assertEquals(WorkRetryPolicy.Decision.GAVE_UP.label, diagnostics.workerRuns().single().result)
    }

    @Test
    fun `foreground info targets the data sync service with the follow-up notification id`() = runTest {
        val info = worker(outcome(configured = false)).getForegroundInfo()

        assertEquals(QuotaNotificationService.NOTIFICATION_ID + 1, info.notificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
    }
}
