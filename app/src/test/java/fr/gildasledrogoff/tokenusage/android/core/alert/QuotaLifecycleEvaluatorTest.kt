package fr.gildasledrogoff.tokenusage.core.alert

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class QuotaLifecycleEvaluatorTest {
    private val evaluator = QuotaLifecycleEvaluator()
    private val config = AlertConfig()
    private val now = Instant.parse("2026-11-21T10:00:00Z")

    private fun quota(label: String, used: Double, resetsAt: Instant?) = QuotaInfo(
        service = AiService.CODEX,
        windows = listOf(UsageWindow(label, used, resetsAt)),
        accountId = "a"
    )

    private fun run(q: QuotaInfo, state: LifecycleState, at: Instant = now) =
        evaluator.evaluate(listOf(q), config, state, at)

    @Test
    fun `exhausted then restored without reset`() {
        val resets = now.plus(Duration.ofHours(3))
        val first = run(quota("5-Hour", 1.0, resets), LifecycleState())
        assertEquals(listOf(AlertType.WINDOW_EXHAUSTED), first.alerts.map { it.type })
        val again = run(quota("5-Hour", 1.0, resets), first.state)
        assertTrue(again.alerts.isEmpty())
        val restored = run(quota("5-Hour", 0.5, resets), again.state)
        assertEquals(listOf(AlertType.WINDOW_RESTORED), restored.alerts.map { it.type })
    }

    @Test
    fun `reset does not send restored and weekly reset celebrates`() {
        val oldReset = now.minus(Duration.ofMinutes(5))
        val before = run(quota("7-Day", 1.0, oldReset), LifecycleState(), now.minus(Duration.ofHours(1)))
        val after = run(quota("7-Day", 0.0, now.plus(Duration.ofDays(7))), before.state)
        assertTrue(after.alerts.none { it.type == AlertType.WINDOW_RESTORED })
        assertEquals(1, after.weeklyResets.size)
    }

    @Test
    fun `session reset does not celebrate`() {
        val before = run(quota("5-Hour", 0.4, now.minus(Duration.ofMinutes(5))), LifecycleState(), now.minus(Duration.ofHours(1)))
        val after = run(quota("5-Hour", 0.0, now.plus(Duration.ofHours(5))), before.state)
        assertTrue(after.weeklyResets.isEmpty())
    }

    @Test
    fun `upcoming weekly reset warns once per stage`() {
        val resets = now.plus(Duration.ofHours(20))
        val first = run(quota("7-Day", 0.5, resets), LifecycleState())
        assertEquals(listOf(AlertType.RESET_UPCOMING), first.alerts.map { it.type })
        assertTrue(run(quota("7-Day", 0.5, resets), first.state).alerts.isEmpty())
        val later = run(quota("7-Day", 0.5, resets), first.state, now.plus(Duration.ofMinutes(19 * 60 + 30)))
        assertEquals(listOf(AlertType.RESET_UPCOMING), later.alerts.map { it.type })
    }

    @Test
    fun `no upcoming warning when quota is nearly used or far from reset`() {
        assertTrue(run(quota("7-Day", 0.95, now.plus(Duration.ofHours(2))), LifecycleState()).alerts.isEmpty())
        assertTrue(run(quota("7-Day", 0.2, now.plus(Duration.ofDays(3))), LifecycleState()).alerts.isEmpty())
        assertTrue(run(quota("5-Hour", 0.2, now.plus(Duration.ofMinutes(30))), LifecycleState()).alerts.isEmpty())
    }

    @Test
    fun `disabled rule stays silent`() {
        val disabled = AlertConfig(serviceDefaults = mapOf(AiService.CODEX.name to AlertRule(enabled = false)))
        val result = evaluator.evaluate(listOf(quota("5-Hour", 1.0, now.plusSeconds(60))), disabled, LifecycleState(), now)
        assertTrue(result.alerts.isEmpty())
    }

    @Test
    fun `warning before reset can be turned off`() {
        val off = AlertConfig(warnBeforeReset = false)
        val result = evaluator.evaluate(listOf(quota("7-Day", 0.5, now.plus(Duration.ofHours(20)))), off, LifecycleState(), now)
        assertTrue(result.alerts.isEmpty())
    }

    @Test
    fun `celebration can be turned off`() {
        val off = AlertConfig(celebrateResets = false)
        val before = evaluator.evaluate(
            listOf(quota("7-Day", 1.0, now.minus(Duration.ofMinutes(5)))), off, LifecycleState(), now.minus(Duration.ofHours(1))
        )
        val after = evaluator.evaluate(listOf(quota("7-Day", 0.0, now.plus(Duration.ofDays(7)))), off, before.state, now)
        assertTrue(after.weeklyResets.isEmpty())
    }
}
