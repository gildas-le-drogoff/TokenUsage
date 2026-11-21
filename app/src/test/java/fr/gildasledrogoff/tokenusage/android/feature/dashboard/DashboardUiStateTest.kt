package fr.gildasledrogoff.tokenusage.feature.dashboard

import fr.gildasledrogoff.tokenusage.core.domain.model.AiService
import fr.gildasledrogoff.tokenusage.core.domain.model.AppError
import fr.gildasledrogoff.tokenusage.core.domain.model.DashboardInsight
import fr.gildasledrogoff.tokenusage.core.domain.model.InsightVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DashboardUiStateTest {
    private val now = Instant.parse("2026-11-21T12:00:00Z")

    private fun card(service: AiService = AiService.CLAUDE, accountId: String? = null) = ServiceCardData(
        service = service,
        windows = emptyList(),
        extraUsage = null,
        tier = null,
        accountId = accountId
    )

    @Test
    fun `a card without error has data`() {
        assertTrue(card().hasData)
    }

    @Test
    fun `a card in error has no data unless stale values remain`() {
        val failed = card().copy(error = AppError.NetworkError("offline"))
        assertFalse(failed.hasData)
        assertTrue(failed.copy(staleSince = now).hasData)
    }

    @Test
    fun `card shows pace and curves by default`() {
        val c = card()
        assertTrue(c.showPace)
        assertTrue(c.showCurves)
    }

    @Test
    fun `card visibility follows the hidden service insights`() {
        val visibility = InsightVisibility()
            .with(DashboardInsight.PACE, AiService.CLAUDE, null, visible = false)
        val shown = card().withInsightVisibility(visibility)
        assertFalse(shown.showPace)
        assertTrue(shown.showCurves)
    }

    @Test
    fun `card visibility follows the hidden account insights`() {
        val visibility = InsightVisibility()
            .with(DashboardInsight.CURVES, AiService.CLAUDE, "a1", visible = false)
        assertFalse(card(accountId = "a1").withInsightVisibility(visibility).showCurves)
        assertTrue(card(accountId = "a2").withInsightVisibility(visibility).showCurves)
        assertTrue(card(accountId = null).withInsightVisibility(visibility).showCurves)
    }

    @Test
    fun `visibility is applied to every card of a success state`() {
        val visibility = InsightVisibility().with(DashboardInsight.PACE, AiService.CLAUDE, null, false)
        val state = DashboardUiState.Success(listOf(card(), card(AiService.DEEPSEEK)), now)
        val result = state.withInsightVisibility(visibility) as DashboardUiState.Success
        assertFalse(result.cards[0].showPace)
        assertTrue(result.cards[1].showPace)
        assertEquals(now, result.lastUpdated)
    }

    @Test
    fun `visibility is applied to partial success and offline states and keeps their metadata`() {
        val visibility = InsightVisibility().with(DashboardInsight.PACE, AiService.CLAUDE, null, false)
        val errors = mapOf(AiService.DEEPSEEK to AppError.NetworkError("x"))
        val partial = DashboardUiState.PartialSuccess(listOf(card()), errors, now)
            .withInsightVisibility(visibility) as DashboardUiState.PartialSuccess
        assertFalse(partial.cards.single().showPace)
        assertEquals(errors, partial.errors)

        val offline = DashboardUiState.Offline(listOf(card()), now)
            .withInsightVisibility(visibility) as DashboardUiState.Offline
        assertFalse(offline.cards.single().showPace)
        assertEquals(now, offline.lastUpdated)
    }

    @Test
    fun `loading and error states are returned untouched`() {
        val visibility = InsightVisibility().with(DashboardInsight.PACE, AiService.CLAUDE, null, false)
        val error = DashboardUiState.Error(AppError.NetworkError("x"))
        assertSame(DashboardUiState.Loading, DashboardUiState.Loading.withInsightVisibility(visibility))
        assertSame(error, error.withInsightVisibility(visibility))
    }

    @Test
    fun `ready chart state finds the selected account`() {
        val a = AccountConsumptionUi("a", "A", emptyList(), "USD")
        val b = AccountConsumptionUi("b", "B", emptyList(), "USD")
        assertEquals(b, ConsumptionChartUiState.Ready(listOf(a, b), "b").selected)
        assertNull(ConsumptionChartUiState.Ready(listOf(a, b), "zzz").selected)
    }

    @Test
    fun `error state is not offline by default`() {
        assertFalse(DashboardUiState.Error(AppError.NetworkError("x")).offline)
    }
}
