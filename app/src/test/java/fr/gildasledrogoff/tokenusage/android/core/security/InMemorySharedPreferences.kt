package fr.gildasledrogoff.tokenusage.core.security

import android.content.SharedPreferences


class InMemorySharedPreferences : SharedPreferences {
    val data = LinkedHashMap<String, Any?>()

    override fun getAll(): Map<String, *> = LinkedHashMap(data)
    override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        data[key] as? Set<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val ops = LinkedHashMap<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = also { ops[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = also { ops[key] = values }
        override fun putInt(key: String, value: Int) = also { ops[key] = value }
        override fun putLong(key: String, value: Long) = also { ops[key] = value }
        override fun putFloat(key: String, value: Float) = also { ops[key] = value }
        override fun putBoolean(key: String, value: Boolean) = also { ops[key] = value }
        override fun remove(key: String) = also { ops[key] = null }
        override fun clear() = also { clear = true }
        override fun commit(): Boolean {
            if (clear) data.clear()
            ops.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
