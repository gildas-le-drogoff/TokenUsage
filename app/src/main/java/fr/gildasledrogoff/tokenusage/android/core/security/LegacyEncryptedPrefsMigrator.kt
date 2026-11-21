package fr.gildasledrogoff.tokenusage.core.security
import android.content.Context
import android.content.SharedPreferences
import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.subtle.Base64
import java.io.File
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
internal class LegacyEncryptedPrefsMigrator(private val context: Context) {
    fun migrateInto(target: SharedPreferences) {
        val legacyFile = File(context.dataDir, "shared_prefs/$LEGACY_FILE.xml")
        if (!legacyFile.exists()) return
        val legacy = context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        if (!legacy.contains(KEY_KEYSET_ALIAS) || !legacy.contains(VALUE_KEYSET_ALIAS)) {
            purgeLegacy()
            return
        }
        try {
            val daead = legacyKeyset(KEY_KEYSET_ALIAS)
                .getPrimitive(RegistryConfiguration.get(), DeterministicAead::class.java)
            val aead = legacyKeyset(VALUE_KEYSET_ALIAS)
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            val editor = target.edit()
            legacy.all.forEach { (encryptedKey, raw) ->
                if (encryptedKey == KEY_KEYSET_ALIAS || encryptedKey == VALUE_KEYSET_ALIAS) return@forEach
                val encryptedValue = raw as? String ?: return@forEach
                val entry = runCatching {
                    val key = daead.decryptDeterministically(
                        Base64.decode(encryptedKey, Base64.DEFAULT),
                        LEGACY_FILE.toByteArray()
                    ).toString(Charsets.UTF_8)
                    val plain = aead.decrypt(
                        Base64.decode(encryptedValue, Base64.DEFAULT),
                        encryptedKey.toByteArray(Charsets.UTF_8)
                    )
                    key to LegacyValueDecoder.decode(plain)
                }.getOrNull() ?: return@forEach
                val (key, value) = entry
                if (key == LEGACY_NULL_VALUE) return@forEach
                when (value) {
                    null -> Unit
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            if (editor.commit()) purgeLegacy()
        } catch (_: GeneralSecurityException) {
            purgeLegacy()
        } catch (_: IOException) {
        }
    }
    private fun legacyKeyset(alias: String) =
        AndroidKeysetManager.Builder()
            .withSharedPref(context, alias, LEGACY_FILE)
            .withMasterKeyUri(LEGACY_MASTER_KEY_URI)
            .build()
            .keysetHandle
    private fun purgeLegacy() {
        context.deleteSharedPreferences(LEGACY_FILE)
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(LEGACY_MASTER_KEY_ALIAS)
        }
    }
    companion object {
        const val LEGACY_FILE = "tokenusage_secure_prefs"
        private const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
        private const val LEGACY_MASTER_KEY_URI = "android-keystore://$LEGACY_MASTER_KEY_ALIAS"
        private const val KEY_KEYSET_ALIAS = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
        private const val VALUE_KEYSET_ALIAS = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
        internal const val LEGACY_NULL_VALUE = "__NULL__"
    }
}
internal object LegacyValueDecoder {
    private const val STRING = 0
    private const val STRING_SET = 1
    private const val INT = 2
    private const val LONG = 3
    private const val FLOAT = 4
    private const val BOOLEAN = 5
    fun decode(plain: ByteArray): Any? = try {
        val buffer = ByteBuffer.wrap(plain)
        when (val type = buffer.getInt()) {
            STRING -> readString(buffer).takeUnless { it == LegacyEncryptedPrefsMigrator.LEGACY_NULL_VALUE }
            STRING_SET -> buildSet {
                while (buffer.hasRemaining()) add(readString(buffer))
            }.takeUnless { it == setOf(LegacyEncryptedPrefsMigrator.LEGACY_NULL_VALUE) }
            INT -> buffer.getInt()
            LONG -> buffer.getLong()
            FLOAT -> buffer.getFloat()
            BOOLEAN -> buffer.get() != 0.toByte()
            else -> throw IOException("Unknown legacy type $type")
        }
    } catch (e: BufferUnderflowException) {
        throw IOException("Truncated legacy value", e)
    }
    private fun readString(buffer: ByteBuffer): String {
        val length = buffer.getInt()
        if (length < 0 || length > buffer.remaining()) throw IOException("Invalid legacy length $length")
        val bytes = ByteArray(length).also { buffer.get(it) }
        return bytes.toString(Charsets.UTF_8)
    }
}
