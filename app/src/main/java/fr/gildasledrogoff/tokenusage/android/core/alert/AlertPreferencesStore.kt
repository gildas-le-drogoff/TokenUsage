package fr.gildasledrogoff.tokenusage.core.alert

import android.content.SharedPreferences
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stores the alert rules and the anti-spam memory as JSON in an encrypted
 * [SharedPreferences] (the app's secure store, under its own keys).
 *
 * [prefsProvider] is called lazily so the secure storage is only opened when needed.
 */
class AlertPreferencesStore(private val prefsProvider: () -> SharedPreferences) {

    private val prefs: SharedPreferences by lazy(prefsProvider)
    private val lock = Any()

    private val _config: MutableStateFlow<AlertConfig> by lazy { MutableStateFlow(readConfig()) }

    /** Current rules, updated by every setter. */
    val config: StateFlow<AlertConfig> get() = _config.asStateFlow()

    fun currentConfig(): AlertConfig = _config.value

    fun setServiceRule(service: AiService, rule: AlertRule) = updateConfig {
        it.copy(serviceDefaults = it.serviceDefaults + (service.name to rule))
    }

    /** Sets the rule of one account, or removes its override (back to the service default) with `null`. */
    fun setAccountRule(accountKey: String, rule: AlertRule?) = updateConfig {
        it.copy(accountRules = if (rule == null) it.accountRules - accountKey else it.accountRules + (accountKey to rule))
    }

    fun setForecastWindowDays(days: Int) = updateConfig {
        it.copy(forecastWindowDays = days.coerceIn(MIN_FORECAST_DAYS, MAX_FORECAST_DAYS))
    }

    fun loadState(): AlertState = synchronized(lock) {
        decode(KEY_STATE, AlertState())
    }

    fun saveState(state: AlertState) = synchronized(lock) {
        prefs.edit().putString(KEY_STATE, json.encodeToString(state)).apply()
    }

    /** Forgets everything (rules and memory). */
    fun clear() = synchronized(lock) {
        prefs.edit().remove(KEY_CONFIG).remove(KEY_STATE).apply()
        _config.value = AlertConfig()
    }

    private fun updateConfig(transform: (AlertConfig) -> AlertConfig) = synchronized(lock) {
        val updated = transform(_config.value)
        prefs.edit().putString(KEY_CONFIG, json.encodeToString(updated)).apply()
        _config.value = updated
    }

    private fun readConfig(): AlertConfig = synchronized(lock) { decode(KEY_CONFIG, AlertConfig()) }

    private inline fun <reified T> decode(key: String, default: T): T {
        val raw = prefs.getString(key, null) ?: return default
        return try {
            json.decodeFromString<T>(raw)
        } catch (_: Exception) {
            default
        }
    }

    companion object {
        const val KEY_CONFIG = "alert_config_v1"
        const val KEY_STATE = "alert_state_v1"
        const val MIN_FORECAST_DAYS = 1
        const val MAX_FORECAST_DAYS = 30

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
