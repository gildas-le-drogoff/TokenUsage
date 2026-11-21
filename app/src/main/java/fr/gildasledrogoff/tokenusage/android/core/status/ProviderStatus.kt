package fr.gildasledrogoff.tokenusage.core.status

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import java.time.Instant

enum class StatusIndicator {
    NONE, MINOR, MAJOR, CRITICAL, MAINTENANCE, UNKNOWN;

    val hasIssue: Boolean get() = this != NONE

    companion object {
        fun fromStatuspage(raw: String?): StatusIndicator = when (raw?.lowercase()) {
            "none" -> NONE
            "minor" -> MINOR
            "major" -> MAJOR
            "critical" -> CRITICAL
            "maintenance" -> MAINTENANCE
            else -> UNKNOWN
        }
    }
}

data class ProviderStatus(
    val indicator: StatusIndicator,
    val description: String?,
    val updatedAt: Instant?,
    val pageUrl: String
)

sealed interface StatusSource {
    val pageUrl: String

    data class Statuspage(override val pageUrl: String) : StatusSource

    data class IncidentFeed(override val pageUrl: String, val feedUrl: String) : StatusSource
}

fun AiService.statusSource(): StatusSource? = when (this) {
    AiService.CLAUDE, AiService.CLAUDE_PLATFORM -> StatusSource.Statuspage("https://status.claude.com/")
    AiService.CODEX, AiService.OPENAI -> StatusSource.Statuspage("https://status.openai.com/")
    AiService.COPILOT -> StatusSource.Statuspage("https://www.githubstatus.com/")
    AiService.OPENROUTER ->
        StatusSource.IncidentFeed("https://status.openrouter.ai/", "https://status.openrouter.ai/history.rss")
    AiService.DEEPSEEK ->
        StatusSource.IncidentFeed("https://status.deepseek.com/", "https://status.deepseek.com/feed.rss")
    AiService.GEMINI, AiService.ZAI -> null
}
