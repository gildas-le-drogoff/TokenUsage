package fr.gildasledrogoff.tokenusage.core.status

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter


internal object IncidentFeedParser {
    val STALE_AFTER: Duration = Duration.ofDays(3)

    private val CLOSED = setOf("resolved", "completed", "postmortem", "closed", "scheduled")
    private val ITEM = Regex("<item\\b[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    private val FLASHDUTY_STATUS = Regex("Status:\\s*</strong>\\s*([^<]+)", RegexOption.IGNORE_CASE)
    private val FIRST_STRONG = Regex("<strong>\\s*([^<]+?)\\s*</strong>", RegexOption.IGNORE_CASE)
    private val DOWN = Regex("\\bdown\\b")
    private val NUMERIC_ENTITY = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")

    private data class Item(
        val title: String,
        val category: String?,
        val status: String?,
        val publishedAt: Instant?
    )

    fun parse(body: String, pageUrl: String, now: Instant): ProviderStatus? {
        if (!body.contains("<rss") && !body.contains("<channel")) return null
        val items = ITEM.findAll(body).map { parseItem(it.groupValues[1]) }.toList()
        val open = items.filter { item ->
            val status = item.status?.lowercase() ?: return@filter false
            status !in CLOSED &&
                    (item.publishedAt == null || !item.publishedAt.isBefore(now.minus(STALE_AFTER)))
        }
        val worst = open.maxByOrNull { severity(indicatorOf(it)) }
        return ProviderStatus(
            indicator = worst?.let(::indicatorOf) ?: StatusIndicator.NONE,
            description = worst?.title?.takeIf { it.isNotBlank() },
            updatedAt = worst?.publishedAt ?: items.mapNotNull { it.publishedAt }.maxOrNull(),
            pageUrl = pageUrl
        )
    }

    private fun parseItem(raw: String): Item {
        val description = tag(raw, "description").orEmpty()
        val status = (FLASHDUTY_STATUS.find(description) ?: FIRST_STRONG.find(description))
            ?.groupValues?.get(1)?.trim()?.trimEnd(':')
        return Item(
            title = tag(raw, "title").orEmpty(),
            category = tag(raw, "category")?.lowercase(),
            status = status,
            publishedAt = tag(raw, "pubDate")?.let { date ->
                runCatching {
                    ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                }.getOrNull()
            }
        )
    }

    private fun severity(indicator: StatusIndicator): Int = when (indicator) {
        StatusIndicator.NONE -> 0
        StatusIndicator.UNKNOWN -> 1
        StatusIndicator.MAINTENANCE -> 2
        StatusIndicator.MINOR -> 3
        StatusIndicator.MAJOR -> 4
        StatusIndicator.CRITICAL -> 5
    }

    private fun indicatorOf(item: Item): StatusIndicator {
        val status = item.status?.lowercase().orEmpty()
        val title = item.title.lowercase()
        if (item.category == "maintenance" || status == "in progress" || status == "verifying" ||
            "maintenance" in title || "维护" in title
        ) {
            return StatusIndicator.MAINTENANCE
        }
        return when {
            "major outage" in title || "全面中断" in title || "完全不可用" in title -> StatusIndicator.CRITICAL
            "partial" in title || "outage" in title || "unavailable" in title || DOWN.containsMatchIn(title) ||
                    "中断" in title || "不可用" in title -> StatusIndicator.MAJOR
            else -> StatusIndicator.MINOR
        }
    }

    private fun tag(raw: String, name: String): String? {
        val match = Regex("<$name\\b[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(raw)
            ?: return null
        val content = match.groupValues[1].trim()
        return if (content.startsWith("<![CDATA[")) {
            content.removePrefix("<![CDATA[").removeSuffix("]]>")
        } else {
            decodeEntities(content)
        }
    }

    private fun decodeEntities(text: String): String =
        NUMERIC_ENTITY.replace(text) { match ->
            val code = match.groupValues[1]
            val value = if (code.startsWith("x")) code.drop(1).toIntOrNull(16) else code.toIntOrNull()
            value?.let { String(Character.toChars(it)) } ?: match.value
        }
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
}
