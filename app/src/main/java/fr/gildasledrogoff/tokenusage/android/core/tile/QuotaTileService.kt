package fr.gildasledrogoff.tokenusage.core.tile
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.LockScreenVisibility
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import fr.gildasledrogoff.tokenusage.core.security.EncryptedPrefsManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
@AndroidEntryPoint
class QuotaTileService : TileService() {
    @Inject
    lateinit var prefsManager: EncryptedPrefsManager
    @Inject
    lateinit var displayPreferences: DisplayPreferencesRepository
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }
    override fun onClick() {
        super.onClick()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tokenusage://dashboard")).apply {
            setPackage(packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapseLegacy(intent)
        }
    }
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseLegacy(intent: Intent) {
        startActivityAndCollapse(intent)
    }
    private fun updateTile() {
        val tile = qsTile ?: return
        val hasAnyCredential = AiService.entries.any { prefsManager.hasCredential(it) }
        if (!hasAnyCredential) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = getString(R.string.tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_tap_to_setup)
            }
            tile.updateTile()
            return
        }
        tile.state = Tile.STATE_ACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (detailsHiddenOnLockScreen()) null else buildSummarySubtitle()
        }
        tile.updateTile()
    }
    private fun detailsHiddenOnLockScreen(): Boolean =
        isLocked &&
            displayPreferences.lockScreenVisibility.value != LockScreenVisibility.SHOW_ALL
    private fun buildSummarySubtitle(): String {
        val services = AiService.entries.filter { prefsManager.hasCredential(it) }
        return services.joinToString(" | ") { it.displayName }
    }
}
