package fr.gildasledrogoff.tokenusage.core.widget

import android.content.Context
import android.content.SharedPreferences
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class WidgetPrefsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("tokenusage_widget_prefs", Context.MODE_PRIVATE)
    }


    fun saveSelectedServices(appWidgetId: Int, services: Set<AiService>) {
        val key = "widget_${appWidgetId}_services"
        prefs.edit().putStringSet(key, services.map { it.name }.toSet()).commit()
    }

    fun getSelectedServices(appWidgetId: Int): Set<AiService> {
        val key = "widget_${appWidgetId}_services"
        val names = prefs.getStringSet(key, null) ?: return emptySet()
        return names.mapNotNull { name ->
            try { AiService.valueOf(name) } catch (_: Exception) { null }
        }.toSet()
    }

    fun deleteWidgetConfig(appWidgetId: Int) {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith("widget_${appWidgetId}_") }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    
    fun removeServiceEverywhere(service: AiService): Boolean {
        val editor = prefs.edit()
        var selectionChanged = false
        for (key in prefs.all.keys.filter { it.startsWith("widget_") && it.endsWith("_services") }) {
            val appWidgetId = key.removePrefix("widget_").removeSuffix("_services").toIntOrNull() ?: continue
            val selected = getSelectedServices(appWidgetId)
            val remaining = selected.filter { it != service }.toSet()
            if (remaining.size != selected.size) {
                editor.putStringSet(key, remaining.map { it.name }.toSet())
                selectionChanged = true
            }
        }
        prefs.all.keys.filter { it.startsWith("cache_${service.name}_") }.forEach { editor.remove(it) }
        editor.apply()
        return selectionChanged
    }


    fun cacheAccounts(service: AiService, accounts: List<CachedAccount>) {
        val prefix = cachePrefix(service)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.putString("${prefix}accounts", json.encodeToString(accounts))
        editor.putLong("${prefix}updated_at", System.currentTimeMillis())
        editor.apply()
    }

    fun getCachedAccounts(service: AiService): List<CachedAccount> {
        val prefix = cachePrefix(service)
        val raw = prefs.getString("${prefix}accounts", null)
            ?: return listOfNotNull(legacyCachedAccount(service))
        return runCatching { json.decodeFromString<List<CachedAccount>>(raw) }.getOrDefault(emptyList())
    }

    fun getCachedUpdatedAt(service: AiService): Long {
        return prefs.getLong("${cachePrefix(service)}updated_at", 0L)
    }


    private fun legacyCachedAccount(service: AiService): CachedAccount? {
        val prefix = cachePrefix(service)
        val labels = prefs.getString("${prefix}labels", null)
            ?.split(",")?.filter { it.isNotEmpty() }
            .orEmpty()
        val tier = prefs.getString("${prefix}tier", null)
        val balanceText = prefs.getString("${prefix}balance_text", null)
        if (labels.isEmpty() && tier == null && balanceText == null) return null
        return CachedAccount(
            tier = tier,
            balanceText = balanceText,
            windows = labels.map { label ->
                CachedWindow(
                    label = label,
                    utilization = prefs.getFloat("${prefix}${label}_util", 0f),
                    resetsAtEpochSecond = prefs.getLong("${prefix}${label}_resets", -1L).takeIf { it > 0 }
                )
            }
        )
    }

    private fun cachePrefix(service: AiService) = "cache_${service.name}_"

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}


@Serializable
data class CachedAccount(
    val accountLabel: String? = null,
    val accountNumber: Int? = null,
    val tier: String? = null,
    val windows: List<CachedWindow> = emptyList(),
    val balanceText: String? = null
)

@Serializable
data class CachedWindow(
    val label: String,
    val utilization: Float,
    val resetsAtEpochSecond: Long? = null
)
