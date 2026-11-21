package fr.gildasledrogoff.tokenusage.core.alert

import fr.gildasledrogoff.tokenusage.core.domain.model.AccountBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.CreditBalance
import fr.gildasledrogoff.tokenusage.core.domain.model.QuotaInfo
import fr.gildasledrogoff.tokenusage.core.domain.model.UsageWindow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class QuotaAlertEvaluatorTest {

    private class FakeSeries(val points: MutableMap<String, List<TimedValue>> = mutableMapOf()) : BalanceSeriesSource {
        override suspend fun series(service: AiService, accountId: String, since: Instant): List<TimedValue> =
            points["${service.name}:$accountId"].orEmpty().filter { !it.at.isBefore(since) }
    }

    private val clock = TestClock()
    private val series = FakeSeries()
    private val evaluator = QuotaAlertEvaluator(DepletionPredictor(clock), series, clock)
    private var state = AlertState()

    private fun window(utilization: Double, label: String = "5h", resetsInHours: Long? = 5) = UsageWindow(
        label = label,
        utilization = utilization,
        resetsAt = resetsInHours?.let { clock.now.plus(Duration.ofHours(it)) }
    )

    private fun quota(
        vararg windows: UsageWindow,
        service: AiService = AiService.CLAUDE,
        accountId: String? = "a1",
        label: String? = "Perso",
        balances: List<AccountBalance> = emptyList()
    ) = QuotaInfo(
        service = service,
        windows = windows.toList(),
        accountBalances = balances,
        fetchedAt = clock.now,
        accountId = accountId,
        accountLabel = label
    )

    private fun balance(total: Double, currency: String = "USD") =
        AccountBalance("", CreditBalance(currency, total, null, null, isAvailable = true))

    private suspend fun run(config: AlertConfig, vararg quotas: QuotaInfo): List<QuotaAlert> {
        val evaluation = evaluator.evaluate(quotas.toList(), config, state)
        state = evaluation.state
        return evaluation.alerts
    }

    private val default80 = AlertConfig()

    // --- quota threshold ---------------------------------------------------------------------

    @Test
    fun `no alert under the threshold`() = runTest {
        assertTrue(run(default80, quota(window(0.79))).isEmpty())
    }

    @Test
    fun `alert at the threshold carries the account and the value`() = runTest {
        val alerts = run(default80, quota(window(0.8)))

        assertEquals(1, alerts.size)
        val alert = alerts.single()
        assertEquals(AlertType.QUOTA_THRESHOLD, alert.type)
        assertEquals("CLAUDE:a1", alert.accountKey)
        assertEquals("Perso", alert.accountLabel)
        assertEquals("5h", alert.windowLabel)
        assertEquals(0.8, alert.currentValue, 1e-9)
        assertEquals(0.8, alert.threshold!!, 1e-9)
    }

    @Test
    fun `alert is not repeated while above the threshold`() = runTest {
        assertEquals(1, run(default80, quota(window(0.85))).size)

        clock.advance(Duration.ofMinutes(15))
        assertTrue(run(default80, quota(window(0.9))).isEmpty())
        clock.advance(Duration.ofMinutes(15))
        assertTrue(run(default80, quota(window(0.95))).isEmpty())
    }

    @Test
    fun `alert fires again after going back under the threshold`() = runTest {
        run(default80, quota(window(0.85)))
        clock.advance(Duration.ofMinutes(15))
        assertTrue(run(default80, quota(window(0.5))).isEmpty())
        clock.advance(Duration.ofMinutes(15))

        assertEquals(1, run(default80, quota(window(0.82))).size)
    }

    @Test
    fun `alert fires again after the window was reset even if always above the threshold`() = runTest {
        run(default80, quota(window(0.9, resetsInHours = 1)))
        clock.advance(Duration.ofHours(2))

        // The announced reset passed; the window now announces the next one and is high again.
        val alerts = run(default80, quota(window(0.85, resetsInHours = 5)))

        assertEquals(1, alerts.size)
    }

    @Test
    fun `one alert per account even when several windows are over the threshold`() = runTest {
        val alerts = run(default80, quota(window(0.85, "5h"), window(0.95, "7d", resetsInHours = 100)))

        assertEquals(1, alerts.size)
        assertEquals("7d", alerts.single().windowLabel)
    }

    @Test
    fun `a second window crossing later is alerted on its own`() = runTest {
        run(default80, quota(window(0.85, "5h"), window(0.2, "7d", resetsInHours = 100)))
        clock.advance(Duration.ofMinutes(30))

        val alerts = run(default80, quota(window(0.86, "5h"), window(0.81, "7d", resetsInHours = 100)))

        assertEquals(listOf("7d"), alerts.map { it.windowLabel })
    }

    @Test
    fun `window filter restricts the alert to one window`() = runTest {
        val config = AlertConfig(accountRules = mapOf("CLAUDE:a1" to AlertRule(quotaWindowLabel = "7d")))

        assertTrue(run(config, quota(window(0.95, "5h"), window(0.1, "7d", resetsInHours = 100))).isEmpty())
        clock.advance(Duration.ofMinutes(15))
        assertEquals(1, run(config, quota(window(0.95, "5h"), window(0.9, "7d", resetsInHours = 100))).size)
    }

    @Test
    fun `quota threshold can be switched off`() = runTest {
        val config = AlertConfig(serviceDefaults = mapOf("CLAUDE" to AlertRule(quotaThreshold = null)))

        assertTrue(run(config, quota(window(1.0))).isEmpty())
    }

    @Test
    fun `disabled rule gives no alert`() = runTest {
        val config = AlertConfig(serviceDefaults = mapOf("CLAUDE" to AlertRule(enabled = false)))

        assertTrue(run(config, quota(window(1.0))).isEmpty())
    }

    // --- multi accounts ----------------------------------------------------------------------

    @Test
    fun `every account of a service is evaluated`() = runTest {
        val alerts = run(
            default80,
            quota(window(0.9), accountId = "a1", label = "Perso"),
            quota(window(0.5), accountId = "a2", label = "Pro"),
            quota(window(0.95), accountId = "a3", label = "Autre")
        )

        assertEquals(setOf("CLAUDE:a1", "CLAUDE:a3"), alerts.map { it.accountKey }.toSet())
    }

    @Test
    fun `anti-spam is kept per account`() = runTest {
        run(default80, quota(window(0.9), accountId = "a1"), quota(window(0.5), accountId = "a2"))
        clock.advance(Duration.ofMinutes(15))

        val alerts = run(default80, quota(window(0.92), accountId = "a1"), quota(window(0.85), accountId = "a2"))

        assertEquals(listOf("CLAUDE:a2"), alerts.map { it.accountKey })
    }

    @Test
    fun `account rule overrides the service default`() = runTest {
        val config = AlertConfig(
            serviceDefaults = mapOf("CLAUDE" to AlertRule(quotaThreshold = 0.5)),
            accountRules = mapOf("CLAUDE:a2" to AlertRule(quotaThreshold = 0.95))
        )

        val alerts = run(config, quota(window(0.6), accountId = "a1"), quota(window(0.6), accountId = "a2"))

        assertEquals(listOf("CLAUDE:a1"), alerts.map { it.accountKey })
    }

    @Test
    fun `account without id uses the default key`() = runTest {
        val alerts = run(default80, quota(window(0.9), service = AiService.CODEX, accountId = null, label = null))

        assertEquals("CODEX:default", alerts.single().accountKey)
    }

    @Test
    fun `a failed account does not reset the memory of the others`() = runTest {
        run(default80, quota(window(0.9), accountId = "a1"), quota(window(0.9), accountId = "a2"))
        clock.advance(Duration.ofMinutes(15))
        // a2 failed this time and is absent.
        assertTrue(run(default80, quota(window(0.9), accountId = "a1")).isEmpty())
        clock.advance(Duration.ofMinutes(15))

        assertTrue(run(default80, quota(window(0.9), accountId = "a1"), quota(window(0.9), accountId = "a2")).isEmpty())
    }

    // --- low balance -------------------------------------------------------------------------

    private val lowBalance = AlertConfig(
        serviceDefaults = mapOf("DEEPSEEK" to AlertRule(quotaThreshold = null, balanceThreshold = 5.0))
    )

    private fun deepseek(total: Double, accountId: String? = "d1") = quota(
        service = AiService.DEEPSEEK,
        accountId = accountId,
        label = "Clé",
        balances = listOf(balance(total))
    )

    @Test
    fun `low balance alert carries the amount and the currency`() = runTest {
        val alert = run(lowBalance, deepseek(4.0)).single()

        assertEquals(AlertType.LOW_BALANCE, alert.type)
        assertEquals(4.0, alert.currentValue, 1e-9)
        assertEquals(5.0, alert.threshold!!, 1e-9)
        assertEquals("USD", alert.currency)
        assertEquals("DEEPSEEK:d1", alert.accountKey)
    }

    @Test
    fun `no low balance alert above the threshold`() = runTest {
        assertTrue(run(lowBalance, deepseek(5.01)).isEmpty())
    }

    @Test
    fun `low balance alert is not repeated and re-arms after a top-up`() = runTest {
        assertEquals(1, run(lowBalance, deepseek(4.0)).size)
        clock.advance(Duration.ofHours(1))
        assertTrue(run(lowBalance, deepseek(3.0)).isEmpty())
        clock.advance(Duration.ofHours(1))
        assertTrue(run(lowBalance, deepseek(50.0)).isEmpty())
        clock.advance(Duration.ofHours(1))

        assertEquals(1, run(lowBalance, deepseek(4.5)).size)
    }

    @Test
    fun `low balance is evaluated for every account`() = runTest {
        val alerts = run(lowBalance, deepseek(4.0, "d1"), deepseek(40.0, "d2"), deepseek(1.0, "d3"))

        assertEquals(setOf("DEEPSEEK:d1", "DEEPSEEK:d3"), alerts.map { it.accountKey }.toSet())
    }

    // --- depletion forecast ------------------------------------------------------------------

    private val depletion24h = AlertConfig(
        serviceDefaults = mapOf(
            "DEEPSEEK" to AlertRule(quotaThreshold = null, depletionHorizonHours = 24),
            "CLAUDE" to AlertRule(quotaThreshold = null, depletionHorizonHours = 3)
        )
    )

    private fun hoursAgo(h: Long) = clock.now.minus(Duration.ofHours(h))

    @Test
    fun `balance depletion alert from the stored history`() = runTest {
        // 10 per day, 12 left: empty in ~29 h... put 8 left: ~19 h < 24 h.
        series.points["DEEPSEEK:d1"] = listOf(
            TimedValue(38.0, hoursAgo(72)), TimedValue(28.0, hoursAgo(48)),
            TimedValue(18.0, hoursAgo(24)), TimedValue(8.0, hoursAgo(0).minusSeconds(1))
        )

        val alert = run(depletion24h, deepseek(8.0)).single()

        assertEquals(AlertType.DEPLETION_FORECAST, alert.type)
        val hours = Duration.between(clock.now, alert.depletionAt).toMinutes() / 60.0
        assertEquals(19.2, hours, 0.1)
    }

    @Test
    fun `no balance depletion alert beyond the horizon`() = runTest {
        series.points["DEEPSEEK:d1"] = listOf(
            TimedValue(130.0, hoursAgo(72)), TimedValue(120.0, hoursAgo(48)),
            TimedValue(110.0, hoursAgo(24)), TimedValue(100.0, hoursAgo(0).minusSeconds(1))
        )

        assertTrue(run(depletion24h, deepseek(100.0)).isEmpty())
    }

    @Test
    fun `no balance depletion alert when the history is insufficient or flat`() = runTest {
        assertTrue(run(depletion24h, deepseek(8.0)).isEmpty())

        series.points["DEEPSEEK:d1"] = listOf(TimedValue(8.0, hoursAgo(48)), TimedValue(8.0, hoursAgo(24)))
        clock.advance(Duration.ofMinutes(10))
        assertTrue(run(depletion24h, deepseek(8.0)).isEmpty())
    }

    @Test
    fun `balance depletion ignores a top-up in the history`() = runTest {
        series.points["DEEPSEEK:d1"] = listOf(
            TimedValue(1.0, hoursAgo(72)), TimedValue(500.0, hoursAgo(50)),
            TimedValue(18.0, hoursAgo(48)), TimedValue(8.0, hoursAgo(24)), TimedValue(2.0, hoursAgo(0).minusSeconds(1))
        )

        // The top-up (1 -> 500) is a break, not a negative consumption. 500 -> 18 is a drop of the
        // same series: the speed is measured after the top-up only.
        val alert = run(depletion24h, deepseek(2.0)).single()
        assertEquals(AlertType.DEPLETION_FORECAST, alert.type)
    }

    @Test
    fun `balance depletion falls back on its own measurements without stored history`() = runTest {
        val config = AlertConfig(
            serviceDefaults = mapOf("OPENROUTER" to AlertRule(quotaThreshold = null, depletionHorizonHours = 24))
        )
        fun openRouter(total: Double) = quota(
            service = AiService.OPENROUTER, accountId = null, label = null, balances = listOf(balance(total))
        )

        assertTrue(run(config, openRouter(20.0)).isEmpty())
        clock.advance(Duration.ofHours(2))
        // 20 -> 10 in two hours: empty in two more hours.
        val alert = run(config, openRouter(10.0)).single()

        assertEquals(AlertType.DEPLETION_FORECAST, alert.type)
        assertEquals("OPENROUTER:default", alert.accountKey)
    }

    private val depletion2h = AlertConfig(
        serviceDefaults = mapOf("CLAUDE" to AlertRule(quotaThreshold = null, depletionHorizonHours = 2))
    )

    @Test
    fun `quota depletion alert is raised from successive measurements`() = runTest {
        // Usage 20 % -> 40 % -> 60 % in 2 h: full in 2 h, before the 5 h reset.
        assertTrue(run(depletion2h, quota(window(0.2))).isEmpty())
        clock.advance(Duration.ofHours(1))
        // Still 3 h away from the exhaustion: beyond the 2 h horizon.
        assertTrue(run(depletion2h, quota(window(0.4, resetsInHours = 4))).isEmpty())
        clock.advance(Duration.ofHours(1))
        val alerts = run(depletion2h, quota(window(0.6, resetsInHours = 3)))

        val forecast = alerts.single()
        assertEquals(AlertType.DEPLETION_FORECAST, forecast.type)
        assertEquals(2.0, Duration.between(clock.now, forecast.depletionAt).toMinutes() / 60.0, 0.1)
        assertEquals("5h", forecast.windowLabel)
        assertEquals("CLAUDE:a1", forecast.accountKey)
    }

    @Test
    fun `no quota depletion alert when the quota resets before the exhaustion`() = runTest {
        run(depletion24h, quota(window(0.2, resetsInHours = 2)))
        clock.advance(Duration.ofHours(1))
        run(depletion24h, quota(window(0.4, resetsInHours = 1)))
        clock.advance(Duration.ofMinutes(30))

        // 20 % per hour -> full in ~1.5 h, but the window resets in 30 min.
        val alerts = run(depletion24h, quota(window(0.5, resetsInHours = 0)))

        assertTrue(alerts.none { it.type == AlertType.DEPLETION_FORECAST })
    }

    @Test
    fun `quota depletion alert is not repeated`() = runTest {
        run(depletion2h, quota(window(0.2)))
        clock.advance(Duration.ofHours(1))
        run(depletion2h, quota(window(0.4)))
        clock.advance(Duration.ofHours(1))
        val first = run(depletion2h, quota(window(0.6)))
        clock.advance(Duration.ofMinutes(15))
        val second = run(depletion2h, quota(window(0.65)))

        assertEquals(1, first.count { it.type == AlertType.DEPLETION_FORECAST })
        assertTrue(second.none { it.type == AlertType.DEPLETION_FORECAST })
    }

    @Test
    fun `threshold alert carries the predicted depletion date`() = runTest {
        val config = AlertConfig(serviceDefaults = mapOf("CLAUDE" to AlertRule(quotaThreshold = 0.8)))
        run(config, quota(window(0.2, resetsInHours = 10)))
        clock.advance(Duration.ofHours(1))
        run(config, quota(window(0.5, resetsInHours = 9)))
        clock.advance(Duration.ofHours(1))

        val alert = run(config, quota(window(0.8, resetsInHours = 8))).single()

        assertEquals(AlertType.QUOTA_THRESHOLD, alert.type)
        assertNotNull(alert.depletionAt)
    }

    @Test
    fun `no prediction after a window reset`() = runTest {
        run(depletion24h, quota(window(0.5)))
        clock.advance(Duration.ofHours(1))
        run(depletion24h, quota(window(0.7)))
        clock.advance(Duration.ofHours(6))
        // Reset: usage dropped; only one measurement of the new cycle.
        val alerts = run(depletion24h, quota(window(0.05, resetsInHours = 5)))

        assertTrue(alerts.isEmpty())
        assertNull(state.samples.values.flatten().firstOrNull { it.value > 0.5 })
    }

    // --- state -------------------------------------------------------------------------------

    @Test
    fun `old measurements are dropped from the state`() = runTest {
        run(depletion24h, quota(window(0.2)))
        clock.advance(Duration.ofDays(9))

        run(depletion24h, quota(window(0.1), accountId = "other"))

        assertTrue(state.samples.keys.none { it.startsWith("CLAUDE:a1") })
    }

    @Test
    fun `the number of kept measurements is bounded`() = runTest {
        repeat(400) {
            run(depletion24h, quota(window(0.1)))
            clock.advance(Duration.ofMinutes(10))
        }

        assertTrue(state.samples.values.all { it.size <= 96 })
    }
}
