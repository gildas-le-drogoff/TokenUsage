package fr.gildasledrogoff.tokenusage.core.domain.repository

import fr.gildasledrogoff.tokenusage.core.domain.model.InsightVisibility
import fr.gildasledrogoff.tokenusage.core.domain.model.LockScreenVisibility
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaDisplayMode
import fr.gildasledrogoff.tokenusage.core.domain.model.ThemeMode
import kotlinx.coroutines.flow.StateFlow

interface DisplayPreferencesRepository {
    val themeMode: StateFlow<ThemeMode>
    val quotaDisplayMode: StateFlow<QuotaDisplayMode>
    val lockScreenVisibility: StateFlow<LockScreenVisibility>
    val insightVisibility: StateFlow<InsightVisibility>
    val batteryPromptAnswered: Boolean
    fun setThemeMode(mode: ThemeMode)
    fun setQuotaDisplayMode(mode: QuotaDisplayMode)
    fun setLockScreenVisibility(visibility: LockScreenVisibility)
    fun setInsightVisibility(visibility: InsightVisibility)
    fun markBatteryPromptAnswered()
}
