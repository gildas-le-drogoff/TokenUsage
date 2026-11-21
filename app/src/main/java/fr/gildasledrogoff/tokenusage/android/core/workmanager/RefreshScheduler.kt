package fr.gildasledrogoff.tokenusage.core.workmanager
import android.content.Context
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class RefreshScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: EncryptedPrefsManager
) {
    fun apply(minutes: Long) {
        WorkManagerInitializer.schedulePeriodicRefresh(context, minutes)
    }
    fun applySaved() {
        apply(prefs.getRefreshInterval())
    }
}
