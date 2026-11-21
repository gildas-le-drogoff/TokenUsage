package fr.gildasledrogoff.tokenusage.core.backup
import java.text.Normalizer
import kotlin.math.log2
enum class PasswordStrength { VERY_WEAK, WEAK, FAIR, STRONG, VERY_STRONG }
object PasswordStrengthEstimator {
    private const val WEAK_BELOW = 28.0
    private const val FAIR_BELOW = 40.0
    private const val STRONG_BELOW = 55.0
    private const val VERY_STRONG_FROM = 70.0
    private const val REPEAT_WEIGHT = 0.25
    private const val TRIVIAL_CAP_BITS = 10.0
    private val COMMON = setOf(
        "000000", "111111", "123456", "1234567", "12345678", "123456789", "1234567890", "654321", "abc123", "admin", "azerty", "azerty123", "azertyuiop", "bonjour", "iloveyou", "letmein", "motdepasse", "passw0rd", "password", "qwerty", "qwertz", "soleil", "tokenusage", "welcome",
    )
    fun entropyBits(password: String): Double {
        val text = Normalizer.normalize(password, Normalizer.Form.NFC)
        val codePoints = text.codePoints().toArray()
        if (codePoints.isEmpty()) return 0.0
        if (text.lowercase() in COMMON || isTrivialRun(codePoints)) {
            return minOf(TRIVIAL_CAP_BITS, codePoints.size * log2(10.0))
        }
        var digits = false
        var lower = false
        var upper = false
        var ascii = false
        var other = false
        for (cp in codePoints) {
            when {
                cp in '0'.code..'9'.code -> digits = true
                cp in 'a'.code..'z'.code -> lower = true
                cp in 'A'.code..'Z'.code -> upper = true
                cp < 128 -> ascii = true
                else -> other = true
            }
        }
        val pool =
            (if (digits) 10 else 0) + (if (lower) 26 else 0) + (if (upper) 26 else 0) + (if (ascii) 33 else 0) + (if (other) OTHER_POOL else 0)
        val distinct = codePoints.toSet().size
        val effectiveLength = distinct + (codePoints.size - distinct) * REPEAT_WEIGHT
        return effectiveLength * log2(pool.toDouble())
    }
    fun strength(password: String): PasswordStrength {
        if (password.isEmpty()) return PasswordStrength.VERY_WEAK
        val bits = entropyBits(password)
        return when {
            bits < WEAK_BELOW -> PasswordStrength.VERY_WEAK
            bits < FAIR_BELOW -> PasswordStrength.WEAK
            bits < STRONG_BELOW -> PasswordStrength.FAIR
            bits < VERY_STRONG_FROM -> PasswordStrength.STRONG
            else -> PasswordStrength.VERY_STRONG
        }
    }
    private const val OTHER_POOL = 1000
    private fun isTrivialRun(cps: IntArray): Boolean {
        if (cps.size < 3) return false
        val step = cps[1] - cps[0]
        return step in -1..1 && (1 until cps.size).all { cps[it] - cps[it - 1] == step }
    }
}
