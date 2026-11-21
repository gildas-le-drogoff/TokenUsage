package fr.gildasledrogoff.tokenusage.core.domain.model


/**
 * Display identity of one account among [count] accounts of the same service:
 * its user-given label if any, otherwise a 1-based number (only when the
 * service has several accounts, so a lone account stays unlabelled).
 */
fun accountNaming(label: String, index: Int, count: Int): Pair<String?, Int?> {
    val named = label.takeIf { it.isNotBlank() }
    return named to (if (named == null && count > 1) index + 1 else null)
}
