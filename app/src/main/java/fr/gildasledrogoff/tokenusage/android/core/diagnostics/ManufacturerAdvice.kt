package fr.gildasledrogoff.tokenusage.core.diagnostics

/**
 * Constructeurs connus pour tuer agressivement les applications en arrière-plan
 * (liste inspirée de dontkillmyapp.com).
 *
 * Tout est local : [instructionsUrl] n'est affiché que comme **texte** (l'app
 * ne l'ouvre jamais et n'effectue aucun appel réseau). Les consignes détaillées
 * sont des chaînes traduites, choisies via [group].
 */
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

    /**
     * Groupe du constructeur, ou `null` si celui-ci n'est pas connu pour des
     * restrictions particulières (Pixel, Motorola, Sony… : Android « stock »).
     */
    fun forManufacturer(manufacturer: String?): ManufacturerGroup? {
        val name = manufacturer?.trim()?.lowercase().orEmpty()
        if (name.isEmpty()) return null
        return aliases.entries.firstOrNull { (alias, _) -> name == alias || name.startsWith("$alias ") }?.value
    }
}
