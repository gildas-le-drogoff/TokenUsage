package fr.gildasledrogoff.tokenusage.core.domain.model


object Currencies {
    const val USD = "USD"
    const val EUR = "EUR"
    const val CNY = "CNY"

    
    val PROVIDER = listOf(USD, CNY)
}


enum class DisplayCurrency(val code: String?) {
    ORIGINAL(null),
    USD(Currencies.USD),
    EUR(Currencies.EUR),
    CNY(Currencies.CNY);

    companion object {
        fun fromName(name: String?): DisplayCurrency =
            entries.firstOrNull { it.name == name } ?: ORIGINAL
    }
}


enum class FxRateSource {
    
    MANUAL,
    
    FRANKFURTER,
    
    ECB;

    val isRemote: Boolean get() = this != MANUAL

    companion object {
        val DEFAULT = FRANKFURTER

        
        fun fromName(name: String?): FxRateSource =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}


data class FxRate(
    val from: String,
    val to: String,
    val rate: Double,
    val source: FxRateSource,
    val date: String? = null
) {
    fun convert(amount: Double): Double = amount * rate
}
