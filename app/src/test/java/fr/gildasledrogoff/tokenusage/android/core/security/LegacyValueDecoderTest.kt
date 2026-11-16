package fr.gildasledrogoff.tokenusage.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer


class LegacyValueDecoderTest {

    private fun legacyString(type: Int, vararg parts: String): ByteArray {
        val encoded = parts.map { it.toByteArray(Charsets.UTF_8) }
        val buffer = ByteBuffer.allocate(4 + encoded.sumOf { 4 + it.size })
        buffer.putInt(type)
        encoded.forEach { buffer.putInt(it.size); buffer.put(it) }
        return buffer.array()
    }

    @Test
    fun `decodes string`() {
        assertEquals("sk-ant-sid02-abc", LegacyValueDecoder.decode(legacyString(0, "sk-ant-sid02-abc")))
    }

    @Test
    fun `decodes string set`() {
        assertEquals(setOf("a", "bé"), LegacyValueDecoder.decode(legacyString(1, "a", "bé")))
    }

    @Test
    fun `decodes long and boolean`() {
        val long = ByteBuffer.allocate(12).putInt(3).putLong(1_700_000_000L).array()
        val bool = ByteBuffer.allocate(5).putInt(5).put(1).array()
        assertEquals(1_700_000_000L, LegacyValueDecoder.decode(long))
        assertEquals(true, LegacyValueDecoder.decode(bool))
    }

    @Test
    fun `maps legacy null marker to null`() {
        assertNull(LegacyValueDecoder.decode(legacyString(0, "__NULL__")))
    }

    @Test(expected = IOException::class)
    fun `rejects truncated value`() {
        LegacyValueDecoder.decode(ByteBuffer.allocate(6).putInt(3).put(0).put(0).array())
    }
}
