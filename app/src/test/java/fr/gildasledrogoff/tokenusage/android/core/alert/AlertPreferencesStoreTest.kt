package fr.gildasledrogoff.tokenusage.core.alert
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.security.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class AlertPreferencesStoreTest {
    private val backing = InMemorySharedPreferences()
    private fun newStore() = AlertPreferencesStore { backing }
    @Test
    fun `default config has the standard quota threshold and nothing else`() {
        val rule = newStore().currentConfig().ruleFor(AiService.CLAUDE, "CLAUDE:default")
        assertTrue(rule.enabled)
        assertEquals(0.8, rule.quotaThreshold!!, 1e-9)
        assertNull(rule.balanceThreshold)
        assertNull(rule.depletionHorizonHours)
        assertNull(rule.quotaWindowLabel)
    }
    @Test
    fun `service rule is stored and survives a new store instance`() {
        val rule = AlertRule(quotaThreshold = 0.9, balanceThreshold = 2.5, depletionHorizonHours = 48)
        newStore().setServiceRule(AiService.DEEPSEEK, rule)
        val reloaded = newStore().currentConfig()
        assertEquals(rule, reloaded.ruleFor(AiService.DEEPSEEK, "DEEPSEEK:x"))
        assertEquals(AlertRule(), reloaded.ruleFor(AiService.CLAUDE, "CLAUDE:x"))
    }
    @Test
    fun `account rule overrides the service rule and can be removed`() {
        val store = newStore()
        store.setServiceRule(AiService.CLAUDE, AlertRule(quotaThreshold = 0.5))
        store.setAccountRule("CLAUDE:a1", AlertRule(quotaThreshold = 0.95))
        assertEquals(0.95, store.currentConfig().ruleFor(AiService.CLAUDE, "CLAUDE:a1").quotaThreshold!!, 1e-9)
        assertEquals(0.5, store.currentConfig().ruleFor(AiService.CLAUDE, "CLAUDE:a2").quotaThreshold!!, 1e-9)
        store.setAccountRule("CLAUDE:a1", null)
        assertEquals(0.5, store.currentConfig().ruleFor(AiService.CLAUDE, "CLAUDE:a1").quotaThreshold!!, 1e-9)
    }
    @Test
    fun `a disabled threshold is kept as null`() {
        newStore().setServiceRule(AiService.CODEX, AlertRule(quotaThreshold = null))
        assertNull(newStore().currentConfig().ruleFor(AiService.CODEX, "CODEX:default").quotaThreshold)
    }
    @Test
    fun `config flow follows the setters`() {
        val store = newStore()
        store.setServiceRule(AiService.ZAI, AlertRule(enabled = false))
        assertFalse(store.config.value.ruleFor(AiService.ZAI, "ZAI:default").enabled)
    }
    @Test
    fun `forecast window is kept within bounds`() {
        val store = newStore()
        store.setForecastWindowDays(0)
        assertEquals(AlertPreferencesStore.MIN_FORECAST_DAYS, store.currentConfig().forecastWindowDays)
        store.setForecastWindowDays(1000)
        assertEquals(AlertPreferencesStore.MAX_FORECAST_DAYS, store.currentConfig().forecastWindowDays)
        store.setForecastWindowDays(14)
        assertEquals(14, newStore().currentConfig().forecastWindowDays)
    }
    @Test
    fun `state round trips`() {
        val state = AlertState(
            fired = mapOf("CLAUDE:a|Q|5h" to 1234L),
            samples = mapOf("CLAUDE:a|W|5h" to listOf(TimedSample(0.5, 1000L, 2000L), TimedSample(0.6, 1500L)))
        )
        newStore().saveState(state)
        assertEquals(state, newStore().loadState())
    }
    @Test
    fun `missing or corrupt data fall back on the defaults`() {
        assertEquals(AlertState(), newStore().loadState())
        backing.edit().putString(AlertPreferencesStore.KEY_CONFIG, "{not json").putString(AlertPreferencesStore.KEY_STATE, "[]").apply()
        assertEquals(AlertConfig(), newStore().currentConfig())
        assertEquals(AlertState(), newStore().loadState())
    }
    @Test
    fun `unknown fields written by a newer version are ignored`() {
        backing.edit().putString(
            AlertPreferencesStore.KEY_CONFIG,
            """{"serviceDefaults":{"CLAUDE":{"quotaThreshold":0.7,"somethingNew":true}},"future":1}"""
        ).apply()
        assertEquals(0.7, newStore().currentConfig().ruleFor(AiService.CLAUDE, "CLAUDE:x").quotaThreshold!!, 1e-9)
    }
    @Test
    fun `clear forgets rules and memory`() {
        val store = newStore()
        store.setServiceRule(AiService.CLAUDE, AlertRule(quotaThreshold = 0.5))
        store.saveState(AlertState(fired = mapOf("k" to 1L)))
        store.clear()
        assertEquals(AlertConfig(), store.currentConfig())
        assertEquals(AlertConfig(), newStore().currentConfig())
        assertEquals(AlertState(), newStore().loadState())
    }
    @Test
    fun `nothing is read before the first use`() {
        var opened = false
        val store = AlertPreferencesStore { opened = true; backing }
        assertFalse(opened)
        store.currentConfig()
        assertTrue(opened)
    }
}
