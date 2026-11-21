package fr.gildasledrogoff.tokenusage.core.diagnostics

enum class ManufacturerGroup(val instructionsUrl: String) {
    XIAOMI("https://dontkillmyapp.com/xiaomi"),
    HUAWEI("https://dontkillmyapp.com/huawei"),
    OPPO("https://dontkillmyapp.com/oppo"),
    VIVO("https://dontkillmyapp.com/vivo"),
    SAMSUNG("https://dontkillmyapp.com/samsung"),
    ONEPLUS("https://dontkillmyapp.com/oneplus"),
    OTHER("https://dontkillmyapp.com")
}

object ManufacturerAdvice {
    private val aliases: Map<String, ManufacturerGroup> = mapOf(
        "xiaomi" to ManufacturerGroup.XIAOMI,
        "redmi" to ManufacturerGroup.XIAOMI,
        "poco" to ManufacturerGroup.XIAOMI,
        "huawei" to ManufacturerGroup.HUAWEI,
        "honor" to ManufacturerGroup.HUAWEI,
        "oppo" to ManufacturerGroup.OPPO,
        "realme" to ManufacturerGroup.OPPO,
        "vivo" to ManufacturerGroup.VIVO,
        "iqoo" to ManufacturerGroup.VIVO,
        "samsung" to ManufacturerGroup.SAMSUNG,
        "oneplus" to ManufacturerGroup.ONEPLUS
    )

    fun forManufacturer(manufacturer: String?): ManufacturerGroup? {
        val name = manufacturer?.trim()?.lowercase().orEmpty()
        if (name.isEmpty()) return null
        return aliases.entries.firstOrNull { (alias, _) -> name == alias || name.startsWith("$alias ") }?.value
    }
}
