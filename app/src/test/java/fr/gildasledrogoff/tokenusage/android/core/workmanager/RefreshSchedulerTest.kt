package fr.gildasledrogoff.tokenusage.core.workmanager

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import fr.gildasledrogoff.tokenusage.feature.settings.inMemoryPrefsManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class RefreshSchedulerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun quotaWork(): WorkInfo =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerInitializer.QUOTA_WORK_NAME).get().single()

    @Test
    fun `apply schedules the periodic quota work`() {
        RefreshScheduler(context, inMemoryPrefsManager()).apply(45)

        assertEquals(
            TimeUnit.MINUTES.toMillis(45),
            quotaWork().periodicityInfo?.repeatIntervalMillis
        )
    }

    @Test
    fun `applySaved uses the saved refresh interval`() {
        val prefs = inMemoryPrefsManager()
        prefs.setRefreshInterval(90)

        RefreshScheduler(context, prefs).applySaved()

        assertEquals(
            TimeUnit.MINUTES.toMillis(90),
            quotaWork().periodicityInfo?.repeatIntervalMillis
        )
    }

    @Test
    fun `applySaved with a disabled interval cancels the work`() {
        val prefs = inMemoryPrefsManager()
        val scheduler = RefreshScheduler(context, prefs)
        scheduler.apply(30)
        prefs.setRefreshInterval(0)

        scheduler.applySaved()

        assertEquals(WorkInfo.State.CANCELLED, quotaWork().state)
    }
}
