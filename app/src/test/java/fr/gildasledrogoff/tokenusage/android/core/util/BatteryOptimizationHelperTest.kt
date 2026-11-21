package fr.gildasledrogoff.tokenusage.core.util

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class BatteryOptimizationHelperTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `not ignoring battery optimizations by default`() {
        assertFalse(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context))
    }

    @Test
    fun `ignoring battery optimizations when the system whitelists the package`() {
        val pm = context.getSystemService(PowerManager::class.java)
        shadowOf(pm).setIgnoringBatteryOptimizations(context.packageName, true)

        assertTrue(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context))
    }

    @Test
    fun `missing power manager is reported as not ignoring`() {
        val noPower = mock(Context::class.java)
        `when`(noPower.getSystemService(PowerManager::class.java)).thenReturn(null)

        assertFalse(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(noPower))
    }

    @Test
    fun `request intent targets this package`() {
        val intent = BatteryOptimizationHelper.requestIgnoreBatteryOptimizationsIntent(context)

        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }

    @Test
    fun `settings intent opens the battery optimization list`() {
        val intent = BatteryOptimizationHelper.openBatteryOptimizationSettingsIntent()

        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, intent.action)
        assertNull(intent.data)
    }
}
