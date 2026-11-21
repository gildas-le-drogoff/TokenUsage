package fr.gildasledrogoff.tokenusage.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightVisibilityTest {
    private val pace = DashboardInsight.PACE
    private val curves = DashboardInsight.CURVES

    @Test
    fun `everything is shown by default, including unknown accounts`() {
        val visibility = InsightVisibility()
        for (insight in DashboardInsight.entries) for (service in AiService.entries) {
            assertTrue(visibility.isShownForService(insight, service))
            assertTrue(visibility.isShown(insight, service, "any-account"))
            assertTrue(visibility.isShown(insight, service, null))
        }
    }

    @Test
    fun `hiding at provider level hides every account of that provider only`() {
        val visibility = InsightVisibility().with(pace, AiService.CLAUDE, null, visible = false)
        assertFalse(visibility.isShown(pace, AiService.CLAUDE, "a1"))
        assertFalse(visibility.isShown(pace, AiService.CLAUDE, null))
        assertTrue("the account flag itself is untouched", visibility.isShownForAccount(pace, AiService.CLAUDE, "a1"))
        assertTrue(visibility.isShown(curves, AiService.CLAUDE, "a1"))
        assertTrue(visibility.isShown(pace, AiService.DEEPSEEK, "a1"))
    }

    @Test
    fun `hiding an account leaves the other accounts and the provider visible`() {
        val visibility = InsightVisibility().with(curves, AiService.DEEPSEEK, "d1", visible = false)
        assertFalse(visibility.isShown(curves, AiService.DEEPSEEK, "d1"))
        assertTrue(visibility.isShown(curves, AiService.DEEPSEEK, "d2"))
        assertTrue(visibility.isShownForService(curves, AiService.DEEPSEEK))
        assertTrue(visibility.isShown(pace, AiService.DEEPSEEK, "d1"))
    }

    @Test
    fun `showing again removes the stored key`() {
        val hidden = InsightVisibility()
            .with(pace, AiService.ZAI, null, visible = false)
            .with(pace, AiService.ZAI, "z1", visible = false)
        val restored = hidden
            .with(pace, AiService.ZAI, null, visible = true)
            .with(pace, AiService.ZAI, "z1", visible = true)
        assertEquals(InsightVisibility(), restored)
    }

    @Test
    fun `account flag survives while the provider is hidden`() {
        val visibility = InsightVisibility()
            .with(pace, AiService.CLAUDE, "a1", visible = false)
            .with(pace, AiService.CLAUDE, null, visible = false)
            .with(pace, AiService.CLAUDE, null, visible = true)
        assertFalse(visibility.isShown(pace, AiService.CLAUDE, "a1"))
        assertTrue(visibility.isShown(pace, AiService.CLAUDE, "a2"))
    }

    @Test
    fun `keys of different insights, services and accounts never collide`() {
        val keys = DashboardInsight.entries.flatMap { insight ->
            AiService.entries.flatMap { service ->
                listOf(null, "a1", "a2").map { InsightVisibility.key(insight, service, it) }
            }
        }
        assertEquals(keys.size, keys.toSet().size)
    }
}
