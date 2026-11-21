package fr.gildasledrogoff.tokenusage.core.status

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class IncidentFeedParserTest {
    private val now = Instant.parse("2026-11-21T10:00:00Z")

    private fun rss(vararg items: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>Status</title>${items.joinToString("")}</channel></rss>"""

    private fun openRouterItem(title: String, category: String, status: String, pubDate: String) = """
        <item>
          <title>$title</title>
          <pubDate>$pubDate</pubDate>
          <category>$category</category>
          <description>&lt;p&gt;&lt;small&gt;Nov 21, 09:00 UTC&lt;/small&gt;&lt;br&gt;&lt;strong&gt;$status&lt;/strong&gt; - Update. &lt;/p&gt;&lt;p&gt;&lt;small&gt;Nov 21, 08:00 UTC&lt;/small&gt;&lt;br&gt;&lt;strong&gt;Investigating&lt;/strong&gt; - Looking. &lt;/p&gt;</description>
        </item>"""

    private fun deepSeekItem(title: String, status: String, pubDate: String) = """
        <item>
          <title>$title</title>
          <description>&lt;p&gt;&lt;strong&gt;Status:&lt;/strong&gt; $status&lt;/p&gt;&lt;p&gt;本次问题已解决。&lt;/p&gt;</description>
          <guid>urn:flashduty:change:1</guid>
          <pubDate>$pubDate</pubDate>
        </item>"""

    private fun parse(body: String) = IncidentFeedParser.parse(body, "https://status.example.com/", now)

    @Test
    fun `resolved history is operational`() {
        val status = parse(
            rss(
                openRouterItem("Video webhooks are not being called", "incident", "Resolved", "Mon, 26 Oct 2026 00:07:28 +0000"),
                openRouterItem("Scheduled site maintenance", "maintenance", "Completed", "Sun, 01 Nov 2026 22:00:00 +0000")
            )
        )!!
        assertEquals(StatusIndicator.NONE, status.indicator)
        assertNull(status.description)
        assertEquals(Instant.parse("2026-11-01T22:00:00Z"), status.updatedAt)
    }

    @Test
    fun `open openrouter incident uses its latest update`() {
        val status = parse(
            rss(openRouterItem("Sign-in failures", "incident", "Identified", "Sat, 21 Nov 2026 09:00:00 +0000"))
        )!!
        assertEquals(StatusIndicator.MINOR, status.indicator)
        assertEquals("Sign-in failures", status.description)
    }

    @Test
    fun `maintenance in progress and scheduled`() {
        val inProgress = parse(
            rss(openRouterItem("Site maintenance", "maintenance", "In Progress", "Sat, 21 Nov 2026 09:00:00 +0000"))
        )!!
        assertEquals(StatusIndicator.MAINTENANCE, inProgress.indicator)
        val scheduled = parse(
            rss(openRouterItem("Site maintenance", "maintenance", "Scheduled", "Sat, 21 Nov 2026 09:00:00 +0000"))
        )!!
        assertEquals(StatusIndicator.NONE, scheduled.indicator)
    }

    @Test
    fun `deepseek severity comes from the title and the worst open incident wins`() {
        val degraded = "DeepSeek API 性能下降（DeepSeek API Degraded Performance）"
        val partial = "DeepSeek 网页/API 部分中断（DeepSeek Web/API Partially Unavailable）"
        val status = parse(
            rss(
                deepSeekItem(degraded, "investigating", "Sat, 21 Nov 2026 17:00:00 +0800"),
                deepSeekItem(partial, "monitoring", "Sat, 21 Nov 2026 16:00:00 +0800"),
                deepSeekItem("DeepSeek Web Major Outage", "resolved", "Sat, 21 Nov 2026 15:00:00 +0800")
            )
        )!!
        assertEquals(StatusIndicator.MAJOR, status.indicator)
        assertEquals(partial, status.description)
        assertEquals(Instant.parse("2026-11-21T08:00:00Z"), status.updatedAt)
    }

    @Test
    fun `deepseek resolved incident is operational`() {
        val status = parse(
            rss(deepSeekItem("DeepSeek 网页/Chat 性能下降（Degraded Performance）", "resolved", "Sun, 15 Nov 2026 00:53:58 +0800"))
        )!!
        assertEquals(StatusIndicator.NONE, status.indicator)
    }

    @Test
    fun `stale open incident is ignored`() {
        val status = parse(
            rss(openRouterItem("Forgotten incident", "incident", "Monitoring", "Mon, 26 Oct 2026 00:07:28 +0000"))
        )!!
        assertEquals(StatusIndicator.NONE, status.indicator)
    }

    @Test
    fun `slowdown is not an outage and garbage is rejected`() {
        val status = parse(
            rss(openRouterItem("Slowdown of the activity page", "incident", "Investigating", "Sat, 21 Nov 2026 09:00:00 +0000"))
        )!!
        assertEquals(StatusIndicator.MINOR, status.indicator)
        assertNull(parse("<html>Access Denied</html>"))
        assertEquals(StatusIndicator.NONE, parse(rss())!!.indicator)
    }
}
