package fr.gildasledrogoff.tokenusage.core.security
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
internal object SecureValueCodec {
    private const val FORMAT_VERSION: Int = 1
    private const val TYPE_STRING: Int = 1
    private const val TYPE_INT: Int = 2
    private const val TYPE_LONG: Int = 3
    private const val TYPE_FLOAT: Int = 4
    private const val TYPE_BOOLEAN: Int = 5
    private const val TYPE_STRING_SET: Int = 6
    fun encode(value: Any): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(FORMAT_VERSION)
            when (value) {
                is String -> {
                    out.writeByte(TYPE_STRING)
                    out.writeUtf8(value)
                }
                is Int -> {
                    out.writeByte(TYPE_INT)
                    out.writeInt(value)
                }
                is Long -> {
                    out.writeByte(TYPE_LONG)
                    out.writeLong(value)
                }
                is Float -> {
                    out.writeByte(TYPE_FLOAT)
                    out.writeFloat(value)
                }
                is Boolean -> {
                    out.writeByte(TYPE_BOOLEAN)
                    out.writeBoolean(value)
                }
                is Set<*> -> {
                    out.writeByte(TYPE_STRING_SET)
                    out.writeInt(value.size)
                    value.forEach { out.writeUtf8(it as String) }
                }
                else -> throw IllegalArgumentException("Unsupported preference type: ${value::class}")
            }
        }
        return bytes.toByteArray()
    }
    fun decode(bytes: ByteArray): Any {
        DataInputStream(bytes.inputStream()).use { input ->
            val version = input.readUnsignedByte()
            if (version != FORMAT_VERSION) throw IOException("Unknown value format $version")
            val value: Any = when (val type = input.readUnsignedByte()) {
                TYPE_STRING -> input.readUtf8()
                TYPE_INT -> input.readInt()
                TYPE_LONG -> input.readLong()
                TYPE_FLOAT -> input.readFloat()
                TYPE_BOOLEAN -> input.readBoolean()
                TYPE_STRING_SET -> {
                    val count = input.readInt()
                    if (count < 0) throw IOException("Negative set size")
                    buildSet(count) { repeat(count) { add(input.readUtf8()) } }
                }
                else -> throw IOException("Unknown value type $type")
            }
            if (input.available() != 0) throw IOException("Trailing bytes in value")
            return value
        }
    }
    private fun DataOutputStream.writeUtf8(value: String) {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        writeInt(utf8.size)
        write(utf8)
    }
    private fun DataInputStream.readUtf8(): String {
        val length = readInt()
        if (length < 0 || length > available()) throw IOException("Invalid string length $length")
        return ByteArray(length).also { readFully(it) }.toString(Charsets.UTF_8)
    }
}
