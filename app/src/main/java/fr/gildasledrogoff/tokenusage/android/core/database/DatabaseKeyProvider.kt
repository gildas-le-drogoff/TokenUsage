package fr.gildasledrogoff.tokenusage.core.database

import fr.gildasledrogoff.tokenusage.core.security.SecureStorageFactory
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseKeyProvider @Inject constructor(
    private val storageFactory: SecureStorageFactory
) {
    data class DatabaseKey(val rawKey: ByteArray, val isNew: Boolean)

    @Synchronized
    fun getOrCreate(): DatabaseKey {
        val store = storageFactory.databaseKeys
        store.getString(KEY_NAME, null)
            ?.takeIf { it.length == KEY_BYTES * 2 }
            ?.let { return DatabaseKey(rawKey(it), isNew = false) }
        val hex = ByteArray(KEY_BYTES)
            .also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        check(
            store.edit().putString(KEY_NAME, hex).commit()
        ) { "Unable to persist the database key" }
        return DatabaseKey(rawKey(hex), isNew = true)
    }

    private fun rawKey(hex: String): ByteArray = "x'$hex'".toByteArray(Charsets.US_ASCII)

    private companion object {
        const val KEY_NAME = "sqlcipher_key"
        const val KEY_BYTES = 32
    }
}
