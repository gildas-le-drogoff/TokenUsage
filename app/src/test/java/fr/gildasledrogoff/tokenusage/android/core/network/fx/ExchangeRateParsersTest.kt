package fr.gildasledrogoff.tokenusage.core.network.fx

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExchangeRateParsersTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `frankfurter rate is parsed`() {
        val parsed = ExchangeRateParsers.parseFrankfurter(
            """{"date":"2026-11-15","base":"USD","quote":"EUR","rate":0.90285}""", "USD", json
        )
        assertNotNull(parsed)
        assertEquals(0.90285, parsed!!.rate, 1e-9)
        assertEquals("2026-11-15", parsed.date)
    }

    @Test
    fun `frankfurter rejects another base, quote or a non-positive rate`() {
        assertNull(ExchangeRateParsers.parseFrankfurter("""{"base":"CNY","quote":"EUR","rate":0.13}""", "USD", json))
        assertNull(ExchangeRateParsers.parseFrankfurter("""{"base":"USD","quote":"GBP","rate":0.79}""", "USD", json))
        assertNull(ExchangeRateParsers.parseFrankfurter("""{"base":"USD","quote":"EUR","rate":0}""", "USD", json))
        assertNull(ExchangeRateParsers.parseFrankfurter("""{"message":"not found"}""", "USD", json))
        assertNull(ExchangeRateParsers.parseFrankfurter("<html>", "USD", json))
    }

    @Test
    fun `ecb rates are inverted to EUR per unit`() {
        val parsed = ExchangeRateParsers.parseEcb(ECB_SAMPLE)
        assertNotNull(parsed)
        val (rates, date) = parsed!!
        assertEquals("2026-11-13", date)
        assertEquals(1.0 / 1.1052, rates.getValue("USD"), 1e-9)
        assertEquals(1.0 / 7.3738, rates.getValue("CNY"), 1e-9)
        assertEquals(3, rates.size)
    }

    @Test
    fun `ecb accepts double quotes and rejects empty documents`() {
        val parsed = ExchangeRateParsers.parseEcb("""<Cube time="2026-01-02"><Cube currency="USD" rate="1.25"/></Cube>""")
        assertEquals(0.8, parsed!!.first.getValue("USD"), 1e-9)
        assertEquals("2026-01-02", parsed.second)
        assertNull(ExchangeRateParsers.parseEcb("<gesmes:Envelope/>"))
    }

    companion object {
        val ECB_SAMPLE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01" xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
                <gesmes:subject>Reference rates</gesmes:subject>
                <Cube>
                    <Cube time='2026-11-13'>
                        <Cube currency='USD' rate='1.1052'/>
                        <Cube currency='JPY' rate='176.83'/>
                        <Cube currency='CNY' rate='7.3738'/>
                    </Cube>
                </Cube>
            </gesmes:Envelope>
        """.trimIndent()
    }
}
