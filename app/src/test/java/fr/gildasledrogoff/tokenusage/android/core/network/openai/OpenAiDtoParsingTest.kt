package fr.gildasledrogoff.tokenusage.core.network.openai
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.parse
import fr.gildasledrogoff.tokenusage.core.network.DtoTestSupport.parseFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class OpenAiDtoParsingTest {
    @Test
    fun `nominal costs page`() {
        val page = parseFixture<OpenAiDto.CostsPage>("openai/costs_nominal.json")
        assertTrue(page.hasMore)
        assertEquals("page_EXAMPLE", page.nextPage)
        assertEquals(2, page.data.size)
        val first = page.data[0]
        assertEquals(1794873600L, first.startTime)
        assertEquals(1794960000L, first.endTime)
        assertEquals(2, first.results.size)
        assertEquals(1.2345, first.results[0].amount!!.value, 0.0)
        assertEquals("usd", first.results[0].amount!!.currency)
        assertEquals("gpt-4o", first.results[0].lineItem)
        assertNull(first.results[1].lineItem)
        assertTrue(page.data[1].results.isEmpty())
    }
    @Test
    fun `empty page`() {
        val page = parseFixture<OpenAiDto.CostsPage>("openai/costs_empty.json")
        assertTrue(page.data.isEmpty())
        assertFalse(page.hasMore)
        assertNull(page.nextPage)
    }
    @Test
    fun `empty object`() {
        val page = parse<OpenAiDto.CostsPage>("{}")
        assertTrue(page.data.isEmpty())
        assertNull(page.nextPage)
    }
    @Test
    fun `unknown fields are ignored and integer amounts accepted`() {
        val page = parseFixture<OpenAiDto.CostsPage>("openai/costs_unknown_fields.json")
        val amount = page.data.single().results.single().amount!!
        assertEquals(2.0, amount.value, 0.0)
        assertEquals("eur", amount.currency)
    }
    @Test
    fun `amount defaults when fields are null`() {
        val amount = parse<OpenAiDto.Amount>("""{"value": null, "currency": null}""")
        assertEquals(0.0, amount.value, 0.0)
        assertEquals("usd", amount.currency)
    }
    @Test
    fun `amount as string is accepted`() {
        assertEquals(3.5, parse<OpenAiDto.Amount>("""{"value": "3.5"}""").value, 0.0)
    }
    @Test
    fun `result without amount keeps null`() {
        assertNull(parse<OpenAiDto.CostResult>("""{"line_item": "x"}""").amount)
    }
    @Test
    fun `error body decodes to an empty page`() {
        val page = parseFixture<OpenAiDto.CostsPage>("openai/error_forbidden.json")
        assertTrue(page.data.isEmpty())
        assertFalse(page.hasMore)
    }
}
