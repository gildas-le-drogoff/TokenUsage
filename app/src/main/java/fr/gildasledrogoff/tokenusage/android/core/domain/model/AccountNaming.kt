package fr.gildasledrogoff.tokenusage.core.domain.model

fun accountNaming(label: String, index: Int, count: Int): Pair<String?, Int?> {
    val named = label.takeIf { it.isNotBlank() }
    return named to (if (named == null && count > 1) index + 1 else null)
}
