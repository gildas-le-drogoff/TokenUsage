package fr.gildasledrogoff.tokenusage.core.security
import android.content.SharedPreferences
import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
internal class SecurePreferences(
    private val storeName: String,
    private val backing: SharedPreferences,
    private val valueAead: Aead,
    private val keyDaead: DeterministicAead
) : SharedPreferences {
    private val keyAssociatedData = "$KEY_AAD_PREFIX$storeName".toByteArray(Charsets.UTF_8)
    private val listeners =
        ConcurrentHashMap<SharedPreferences.OnSharedPreferenceChangeListener,
            SharedPreferences.OnSharedPreferenceChangeListener>()
    override fun getAll(): Map<String, *> = buildMap {
        backing.all.forEach { (encryptedKey, raw) ->
            val key = decryptKey(encryptedKey) ?: return@forEach
            val value = (raw as? String)?.let { decryptValue(encryptedKey, it) } ?: return@forEach
            put(key, value)
        }
    }
    override fun getString(key: String, defValue: String?): String? =
        read(key) as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (read(key) as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int): Int = read(key) as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = read(key) as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = read(key) as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        read(key) as? Boolean ?: defValue
    override fun contains(key: String): Boolean = backing.contains(encryptKey(key))
    override fun edit(): SharedPreferences.Editor = SecureEditor()
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        val wrapper = SharedPreferences.OnSharedPreferenceChangeListener { _, encryptedKey ->
            listener.onSharedPreferenceChanged(this, encryptedKey?.let(::decryptKey))
        }
        listeners[listener] = wrapper
        backing.registerOnSharedPreferenceChangeListener(wrapper)
    }
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners.remove(listener)?.let(backing::unregisterOnSharedPreferenceChangeListener)
    }
    private fun read(key: String): Any? {
        val encryptedKey = encryptKey(key)
        val raw = backing.getString(encryptedKey, null) ?: return null
        return decryptValue(encryptedKey, raw)
    }
    private fun encryptKey(key: String): String =
        ENCODER.encodeToString(
            keyDaead.encryptDeterministically(key.toByteArray(Charsets.UTF_8), keyAssociatedData)
        )
    private fun decryptKey(encryptedKey: String): String? = try {
        keyDaead.decryptDeterministically(DECODER.decode(encryptedKey), keyAssociatedData)
            .toString(Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
    private fun valueAssociatedData(encryptedKey: String): ByteArray =
        "$VALUE_AAD_PREFIX$storeName/$encryptedKey".toByteArray(Charsets.UTF_8)
    private fun encryptValue(encryptedKey: String, value: Any): String =
        ENCODER.encodeToString(
            valueAead.encrypt(SecureValueCodec.encode(value), valueAssociatedData(encryptedKey))
        )
    private fun decryptValue(encryptedKey: String, raw: String): Any? = try {
        SecureValueCodec.decode(
            valueAead.decrypt(DECODER.decode(raw), valueAssociatedData(encryptedKey))
        )
    } catch (_: Exception) {
        null
    }
    private inner class SecureEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var clearRequested = false
        override fun putString(key: String, value: String?) = stage(key, value)
        override fun putStringSet(key: String, values: Set<String>?) = stage(key, values?.toSet())
        override fun putInt(key: String, value: Int) = stage(key, value)
        override fun putLong(key: String, value: Long) = stage(key, value)
        override fun putFloat(key: String, value: Float) = stage(key, value)
        override fun putBoolean(key: String, value: Boolean) = stage(key, value)
        override fun remove(key: String) = stage(key, null)
        override fun clear(): SharedPreferences.Editor {
            synchronized(pending) { clearRequested = true }
            return this
        }
        override fun commit(): Boolean = buildBackingEdit().commit()
        override fun apply() = buildBackingEdit().apply()
        private fun stage(key: String, value: Any?): SharedPreferences.Editor {
            synchronized(pending) { pending[key] = value }
            return this
        }
        private fun buildBackingEdit(): SharedPreferences.Editor {
            val editor = backing.edit()
            synchronized(pending) {
                if (clearRequested) editor.clear()
                pending.forEach { (key, value) ->
                    val encryptedKey = encryptKey(key)
                    if (value == null) {
                        editor.remove(encryptedKey)
                    } else {
                        editor.putString(encryptedKey, encryptValue(encryptedKey, value))
                    }
                }
                pending.clear()
                clearRequested = false
            }
            return editor
        }
    }
    private companion object {
        const val KEY_AAD_PREFIX = "tokenusage-prefs-key:"
        const val VALUE_AAD_PREFIX = "tokenusage-prefs-value:"
        val ENCODER: Base64.Encoder = Base64.getEncoder()
        val DECODER: Base64.Decoder = Base64.getDecoder()
    }
}
internal object LockedPreferences : SharedPreferences {
    override fun getAll(): Map<String, *> = emptyMap<String, Any>()
    override fun getString(key: String, defValue: String?): String? = defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = defValue
    override fun getLong(key: String, defValue: Long): Long = defValue
    override fun getFloat(key: String, defValue: Float): Float = defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
    override fun contains(key: String): Boolean = false
    override fun edit(): SharedPreferences.Editor = NoOpEditor
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = Unit
    private object NoOpEditor : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = this
        override fun putStringSet(key: String, values: Set<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
        override fun remove(key: String) = this
        override fun clear() = this
        override fun commit(): Boolean = false
        override fun apply() = Unit
    }
}
