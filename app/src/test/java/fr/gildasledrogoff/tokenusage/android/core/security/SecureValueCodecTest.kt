package fr.gildasledrogoff.tokenusage.core.security
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
class SecureValueCodecTest {
    @Test
    fun `round-trips every supported type`() {
        val values = listOf<Any>("héllo ✓", "", 42, Long.MAX_VALUE, 1.5f, true, false, setOf("a", "b", ""))
        values.forEach { assertEquals(it, SecureValueCodec.decode(SecureValueCodec.encode(it))) }
    }
    @Test
    fun `round-trips strings larger than 64 KiB`() {
        val big = "x".repeat(200_000)
        assertEquals(big, SecureValueCodec.decode(SecureValueCodec.encode(big)))
    }
    @Test(expected = IOException::class)
    fun `rejects unknown format version`() {
        val bytes = SecureValueCodec.encode("value").also { it[0] = 9 }
        SecureValueCodec.decode(bytes)
    }
    @Test(expected = IOException::class)
    fun `rejects truncated payload`() {
        val bytes = SecureValueCodec.encode("value")
        SecureValueCodec.decode(bytes.copyOf(bytes.size - 2))
    }
}
