package fr.gildasledrogoff.tokenusage.core.diagnostics

/**
 * Retire des textes de diagnostic tout ce qui ressemble à un secret : tokens,
 * clés d'API, cookies, en-têtes `Authorization`, JWT, e-mails.
 *
 * Approche volontairement prudente : mieux vaut masquer un texte inoffensif
 * que laisser fuiter un identifiant. Idempotent.
 */
object DiagnosticsSanitizer {

    const val REDACTED = "[redacted]"
    const val MAX_LENGTH = 300

    private val headerLine = Regex(
        """(?i)\b(proxy-authorization|authorization|set-cookie|cookie)\b(\s*[:=]\s*)[^\r\n]*"""
    )
    private val bearer = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{6,}""")
    private val namedSecret = Regex(
        """(?i)(["']?\b(?:session[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|""" +
            """auth[_-]?token|token|api[_-]?key|apikey|client[_-]?secret|secret|password|passwd|key)\b["']?\s*[:=]\s*)""" +
            """(["']?)[^\s"',;&}\]]+"""
    )
    private val knownShapes = listOf(
        Regex("""\bsk-[A-Za-z0-9_-]{8,}"""),
        Regex("""\b(?:gh[pousr]_|github_pat_)[A-Za-z0-9_]{8,}"""),
        Regex("""\bya29\.[A-Za-z0-9._-]+"""),
        Regex("""\bAIza[0-9A-Za-z_-]{20,}"""),
        Regex("""\bxox[abprs]-[A-Za-z0-9-]{8,}"""),
        Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.?[A-Za-z0-9_-]*""")
    )
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")
    private val opaqueLongString = Regex("""(?<![A-Za-z0-9_\-+/=])[A-Za-z0-9_\-+/=]{32,}(?![A-Za-z0-9_\-+/=])""")

    fun sanitize(text: String?, maxLength: Int = MAX_LENGTH): String {
        if (text.isNullOrEmpty()) return ""
        var out: String = text
        out = headerLine.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$REDACTED" }
        out = bearer.replace(out) { "${it.groupValues[1]} $REDACTED" }
        out = namedSecret.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}$REDACTED" }
        knownShapes.forEach { out = it.replace(out, REDACTED) }
        out = email.replace(out, "[email]")
        out = opaqueLongString.replace(out, REDACTED)
        return if (out.length > maxLength) out.take(maxLength) + "…" else out
    }
}
