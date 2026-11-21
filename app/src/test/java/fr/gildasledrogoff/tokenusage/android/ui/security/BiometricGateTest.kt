package fr.gildasledrogoff.tokenusage.ui.security

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    application = Application::class,
    qualifiers = "en-w411dp-h891dp"
)
class BiometricGateTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `there are four availability states`() {
        assertEquals(
            listOf("READY", "NO_HARDWARE", "NONE_ENROLLED", "UNAVAILABLE"),
            BiometricGate.Availability.entries.map { it.name }
        )
    }

    @Test
    fun `a device able to authenticate is reported ready`() {
        biometricShadow().setCanAuthenticate(true)

        assertEquals(BiometricGate.Availability.READY, BiometricGate.availability(context))
    }

    @Test
    fun `a device without authenticator is reported without hardware`() {
        biometricShadow().setCanAuthenticate(false)

        assertEquals(BiometricGate.Availability.NO_HARDWARE, BiometricGate.availability(context))
    }

    private fun biometricShadow(): ShadowBiometricManager =
        Shadow.extract(context.getSystemService(android.hardware.biometrics.BiometricManager::class.java))
}
