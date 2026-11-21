package fr.gildasledrogoff.tokenusage.core.workmanager

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class WorkManagerInitializerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun infos(name: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    @Test
    fun `unique work names list both periodic works`() {
        assertEquals(
            listOf(WorkManagerInitializer.QUOTA_WORK_NAME, WorkManagerInitializer.TOKEN_WORK_NAME),
            WorkManagerInitializer.UNIQUE_WORK_NAMES
        )
    }

    @Test
    fun `schedulePeriodicRefresh enqueues a connected periodic quota work`() {
        WorkManagerInitializer.schedulePeriodicRefresh(context, 60)

        val info = infos(WorkManagerInitializer.QUOTA_WORK_NAME).single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue("quota_refresh" in info.tags)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals(TimeUnit.MINUTES.toMillis(60), info.periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun `schedulePeriodicRefresh raises too short intervals to the minimum`() {
        WorkManagerInitializer.schedulePeriodicRefresh(context, 1)

        val info = infos(WorkManagerInitializer.QUOTA_WORK_NAME).single()
        assertEquals(
            TimeUnit.MINUTES.toMillis(WorkManagerInitializer.MIN_INTERVAL_MINUTES),
            info.periodicityInfo?.repeatIntervalMillis
        )
    }

    @Test
    fun `schedulePeriodicRefresh with zero cancels the quota work`() {
        WorkManagerInitializer.schedulePeriodicRefresh(context, 30)
        WorkManagerInitializer.schedulePeriodicRefresh(context, 0)

        assertEquals(WorkInfo.State.CANCELLED, infos(WorkManagerInitializer.QUOTA_WORK_NAME).single().state)
    }

    @Test
    fun `schedulePeriodicRefresh with a negative interval enqueues nothing`() {
        WorkManagerInitializer.schedulePeriodicRefresh(context, -5)

        assertTrue(infos(WorkManagerInitializer.QUOTA_WORK_NAME).isEmpty())
    }

    @Test
    fun `rescheduling updates the existing quota work instead of duplicating it`() {
        WorkManagerInitializer.schedulePeriodicRefresh(context, 30)
        WorkManagerInitializer.schedulePeriodicRefresh(context, 120)

        val info = infos(WorkManagerInitializer.QUOTA_WORK_NAME).single()
        assertEquals(TimeUnit.MINUTES.toMillis(120), info.periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun `scheduleTokenRefresh enqueues a connected periodic token work with the default interval`() {
        WorkManagerInitializer.scheduleTokenRefresh(context)

        val info = infos(WorkManagerInitializer.TOKEN_WORK_NAME).single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue("token_refresh" in info.tags)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals(TimeUnit.MINUTES.toMillis(30), info.periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun `scheduleTokenRefresh raises too short intervals to the minimum`() {
        WorkManagerInitializer.scheduleTokenRefresh(context, 5)

        val info = infos(WorkManagerInitializer.TOKEN_WORK_NAME).single()
        assertEquals(
            TimeUnit.MINUTES.toMillis(WorkManagerInitializer.MIN_INTERVAL_MINUTES),
            info.periodicityInfo?.repeatIntervalMillis
        )
    }

    @Test
    fun `scheduleTokenRefresh with zero cancels the token work`() {
        WorkManagerInitializer.scheduleTokenRefresh(context)
        WorkManagerInitializer.scheduleTokenRefresh(context, 0)

        assertEquals(WorkInfo.State.CANCELLED, infos(WorkManagerInitializer.TOKEN_WORK_NAME).single().state)
    }

    @Test
    fun `quota and token works are independent`() {
        WorkManagerInitializer.schedulePeriodicRefresh(context, 30)
        WorkManagerInitializer.scheduleTokenRefresh(context, 30)
        WorkManagerInitializer.schedulePeriodicRefresh(context, 0)

        assertEquals(WorkInfo.State.CANCELLED, infos(WorkManagerInitializer.QUOTA_WORK_NAME).single().state)
        assertEquals(WorkInfo.State.ENQUEUED, infos(WorkManagerInitializer.TOKEN_WORK_NAME).single().state)
    }
}
