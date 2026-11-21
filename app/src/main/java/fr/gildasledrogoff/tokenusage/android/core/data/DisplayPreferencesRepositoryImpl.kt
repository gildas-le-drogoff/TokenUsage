package fr.gildasledrogoff.tokenusage.core.data
import android.content.Context
import android.content.SharedPreferences
import fr.gildasledrogoff.tokenusage.core.domain.model.LockScreenVisibility
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import fr.gildasledrogoff.tokenusage.core.domain.repository.DisplayPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class DisplayPreferencesRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context
) : DisplayPreferencesRepository {
    private val prefs: SharedPreferences = prefsOf(context)
    private val _themeMode = MutableStateFlow(ThemeMode.fromName(prefs.getString(KEY_THEME_MODE, null)))
    override val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()
    private val _quotaDisplayMode = MutableStateFlow(readQuotaDisplayMode(prefs))
    override val quotaDisplayMode: StateFlow<QuotaDisplayMode> = _quotaDisplayMode.asStateFlow()
    private val _lockScreenVisibility = MutableStateFlow(
        LockScreenVisibility.fromName(prefs.getString(KEY_LOCK_SCREEN_VISIBILITY, null))
    )
    override val lockScreenVisibility: StateFlow<LockScreenVisibility> = _lockScreenVisibility.asStateFlow()
    override val batteryPromptAnswered: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_PROMPT_ANSWERED, false)
    override fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }
    override fun setQuotaDisplayMode(mode: QuotaDisplayMode) {
        prefs.edit().putString(KEY_QUOTA_DISPLAY_MODE, mode.name).apply()
        _quotaDisplayMode.value = mode
    }
    override fun setLockScreenVisibility(visibility: LockScreenVisibility) {
        prefs.edit().putString(KEY_LOCK_SCREEN_VISIBILITY, visibility.name).apply()
        _lockScreenVisibility.value = visibility
    }
    override fun markBatteryPromptAnswered() {
        prefs.edit().putBoolean(KEY_BATTERY_PROMPT_ANSWERED, true).apply()
    }
    companion object {
        private const val PREFS_NAME = "tokenusage_display_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_QUOTA_DISPLAY_MODE = "quota_display_mode"
        private const val KEY_LOCK_SCREEN_VISIBILITY = "lock_screen_visibility"
        private const val KEY_BATTERY_PROMPT_ANSWERED = "battery_prompt_answered"
        private fun prefsOf(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        private fun readQuotaDisplayMode(prefs: SharedPreferences): QuotaDisplayMode =
            QuotaDisplayMode.fromName(prefs.getString(KEY_QUOTA_DISPLAY_MODE, null))
        fun quotaDisplayMode(context: Context): QuotaDisplayMode = readQuotaDisplayMode(prefsOf(context))
        fun themeMode(context: Context): ThemeMode =
            ThemeMode.fromName(prefsOf(context).getString(KEY_THEME_MODE, null))
    }
}
