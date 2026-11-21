package fr.gildasledrogoff.tokenusage.core.workmanager

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class BootReceiverTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun tokenWork(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerInitializer.TOKEN_WORK_NAME).get()

    @Test
    fun `boot completed schedules the token refresh`() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(WorkInfo.State.ENQUEUED, tokenWork().single().state)
    }

    @Test
    fun `package replaced schedules the token refresh`() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertEquals(WorkInfo.State.ENQUEUED, tokenWork().single().state)
    }

    @Test
    fun `unrelated actions are ignored`() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BATTERY_LOW))

        assertTrue(tokenWork().isEmpty())
    }

    @Test
    fun `a null intent is ignored`() {
        BootReceiver().onReceive(context, null)

        assertTrue(tokenWork().isEmpty())
    }
}
