package fr.gildasledrogoff.tokenusage.core.backup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class PasswordStrengthTest {
    private fun s(p: String) = PasswordStrengthEstimator.strength(p)
    @Test
    fun `trivial passwords are very weak`() {
        assertEquals(PasswordStrength.VERY_WEAK, s(""))
        assertEquals(PasswordStrength.VERY_WEAK, s("123456"))
        assertEquals(PasswordStrength.VERY_WEAK, s("000000"))
        assertEquals(PasswordStrength.VERY_WEAK, s("abcdefgh"))
        assertEquals(PasswordStrength.VERY_WEAK, s("PassWord"))
        assertEquals(PasswordStrength.VERY_WEAK, s("      "))
    }
    @Test
    fun `six random digits are very weak and six lowercase are weak`() {
        assertEquals(PasswordStrength.VERY_WEAK, s("483920"))
        assertEquals(PasswordStrength.WEAK, s("kqzmvx"))
    }
    @Test
    fun `longer and more varied passwords score higher`() {
        assertTrue(s("Tr0ub4dor&3") >= PasswordStrength.STRONG)
        assertTrue(s("correct horse battery staple") >= PasswordStrength.VERY_STRONG)
        assertTrue(s("😀🔐🚀🌵🎸🍕") >= PasswordStrength.FAIR)
    }
    @Test
    fun `repeats do not inflate strength`() {
        assertTrue(s("aaaaaaaaaaaa") <= PasswordStrength.VERY_WEAK)
        assertTrue(PasswordStrengthEstimator.entropyBits("a1b2c3") > PasswordStrengthEstimator.entropyBits("a1a1a1"))
    }
    @Test
    fun `entropy grows with length`() {
        val short = PasswordStrengthEstimator.entropyBits("Xk9#mP")
        val long = PasswordStrengthEstimator.entropyBits("Xk9#mPq2")
        assertTrue(long > short)
    }
}
