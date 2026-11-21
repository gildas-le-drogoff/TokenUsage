package fr.gildasledrogoff.tokenusage.core.status

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService

data class StatusCheckSettings(
    val allProviders: Boolean = false,
    val providers: Set<AiService> = emptySet()
) {
    val isAnyEnabled: Boolean get() = supportedServices.any(::isEnabled)

    fun isEnabled(service: AiService): Boolean =
        service.statusSource() != null && (allProviders || service in providers)

    fun withProvider(service: AiService, enabled: Boolean): StatusCheckSettings =
        copy(providers = if (enabled) providers + service else providers - service)

    companion object {
        val supportedServices: List<AiService> = AiService.entries.filter { it.statusSource() != null }
    }
}
