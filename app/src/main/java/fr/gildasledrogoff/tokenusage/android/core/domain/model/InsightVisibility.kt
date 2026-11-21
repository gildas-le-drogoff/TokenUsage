package fr.gildasledrogoff.tokenusage.core.domain.model

enum class DashboardInsight {
    PACE,

    CURVES
}

data class InsightVisibility(val hiddenKeys: Set<String> = emptySet()) {

    fun isShownForService(insight: DashboardInsight, service: AiService): Boolean =
        key(insight, service, null) !in hiddenKeys

    fun isShownForAccount(insight: DashboardInsight, service: AiService, accountId: String): Boolean =
        key(insight, service, accountId) !in hiddenKeys

    fun isShown(insight: DashboardInsight, service: AiService, accountId: String?): Boolean =
        isShownForService(insight, service) &&
            (accountId == null || isShownForAccount(insight, service, accountId))

    
    fun with(
        insight: DashboardInsight,
        service: AiService,
        accountId: String?,
        visible: Boolean
    ): InsightVisibility {
        val key = key(insight, service, accountId)
        return copy(hiddenKeys = if (visible) hiddenKeys - key else hiddenKeys + key)
    }

    companion object {
        private const val SEPARATOR = ":"

        fun key(insight: DashboardInsight, service: AiService, accountId: String?): String =
            listOfNotNull(insight.name, service.name, accountId).joinToString(SEPARATOR)
    }
}
