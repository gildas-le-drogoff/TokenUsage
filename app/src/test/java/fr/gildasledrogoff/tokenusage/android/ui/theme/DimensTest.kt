package fr.gildasledrogoff.tokenusage.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DimensTest {
    @Test
    fun `width class boundaries are 600 and 840 dp`() {
        assertEquals(WidthClass.COMPACT, widthClassOf(0))
        assertEquals(WidthClass.COMPACT, widthClassOf(599))
        assertEquals(WidthClass.MEDIUM, widthClassOf(600))
        assertEquals(WidthClass.MEDIUM, widthClassOf(839))
        assertEquals(WidthClass.EXPANDED, widthClassOf(840))
        assertEquals(WidthClass.EXPANDED, widthClassOf(2000))
    }

    @Test
    fun `dimens keep the width class they were built for`() {
        WidthClass.entries.forEach { assertEquals(it, Dimens.forWidth(it).widthClass) }
    }

    @Test
    fun `compact dimens`() {
        val d = Dimens.forWidth(WidthClass.COMPACT)

        assertEquals(16.dp, d.screenPadding)
        assertEquals(20.dp, d.cardCorner)
        assertEquals(320.dp, d.cardMinWidth)
        assertEquals(140.dp, d.inlineChartHeight)
    }

    @Test
    fun `medium dimens`() {
        val d = Dimens.forWidth(WidthClass.MEDIUM)

        assertEquals(24.dp, d.screenPadding)
        assertEquals(24.dp, d.cardCorner)
        assertEquals(340.dp, d.cardMinWidth)
        assertEquals(160.dp, d.inlineChartHeight)
    }

    @Test
    fun `expanded dimens`() {
        val d = Dimens.forWidth(WidthClass.EXPANDED)

        assertEquals(32.dp, d.screenPadding)
        assertEquals(380.dp, d.cardMinWidth)
        assertEquals(180.dp, d.inlineChartHeight)
        assertEquals(40.dp, d.serviceIcon)
    }

    @Test
    fun `wider screens never get smaller paddings or cards`() {
        val all = WidthClass.entries.map { Dimens.forWidth(it) }

        all.zipWithNext().forEach { (a, b) ->
            assertTrue(b.screenPadding >= a.screenPadding)
            assertTrue(b.cardPadding >= a.cardPadding)
            assertTrue(b.cardMinWidth >= a.cardMinWidth)
            assertTrue(b.inlineChartHeight >= a.inlineChartHeight)
        }
    }
}
