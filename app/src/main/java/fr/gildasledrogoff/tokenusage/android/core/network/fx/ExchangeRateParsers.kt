package fr.gildasledrogoff.tokenusage.core.network.fx
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
object ExchangeRateEndpoints {
    const val FRANKFURTER_BASE_URL = "https://api.frankfurter.dev/"
    const val ECB_DAILY_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"
    fun frankfurterRatePath(base: String): String = "v2/rate/${base.lowercase()}/eur"
}
object ExchangeRateParsers {
    @Serializable
    private data class FrankfurterRate(
        @SerialName("date") val date: String? = null,
        @SerialName("base") val base: String? = null,
        @SerialName("quote") val quote: String? = null,
        @SerialName("rate") val rate: Double? = null
    )
    data class Parsed(val rate: Double, val date: String?)
    fun parseFrankfurter(body: String, expectedBase: String, json: Json): Parsed? {
        val dto = runCatching { json.decodeFromString(FrankfurterRate.serializer(), body) }.getOrNull()
            ?: return null
        val rate = dto.rate?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        if (dto.base != null && !dto.base.equals(expectedBase, ignoreCase = true)) return null
        if (dto.quote != null && !dto.quote.equals("EUR", ignoreCase = true)) return null
        return Parsed(rate = rate, date = dto.date)
    }
    private val ecbTimePattern = Regex("""<Cube\s+time\s*=\s*['"]([0-9-]+)['"]""")
    private val ecbRatePattern =
        Regex("""<Cube\s+currency\s*=\s*['"]([A-Za-z]{3})['"]\s+rate\s*=\s*['"]([0-9.]+)['"]""")
    fun parseEcb(xml: String): Pair<Map<String, Double>, String?>? {
        val date = ecbTimePattern.find(xml)?.groupValues?.get(1)
        val rates = ecbRatePattern.findAll(xml).mapNotNull { match ->
            val perEur = match.groupValues[2].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                ?: return@mapNotNull null
            match.groupValues[1].uppercase() to 1.0 / perEur
        }.toMap()
        if (rates.isEmpty()) return null
        return rates to date
    }
}
