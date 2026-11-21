package fr.gildasledrogoff.tokenusage.core.database
import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
internal object SqlCipherDatabasePreparer {
    private val SQLITE_PLAINTEXT_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    fun prepare(context: Context, name: String, key: DatabaseKeyProvider.DatabaseKey) {
        val dbFile = context.getDatabasePath(name)
        if (!dbFile.exists()) return
        when {
            isPlaintext(dbFile) -> runCatching { encrypt(context, dbFile, key.rawKey) }
                .onFailure { context.deleteDatabase(name) }
            key.isNew -> context.deleteDatabase(name)
        }
    }
    private fun isPlaintext(file: File): Boolean {
        val header = ByteArray(SQLITE_PLAINTEXT_HEADER.size)
        val read = file.inputStream().use { it.read(header) }
        return read == header.size && header.contentEquals(SQLITE_PLAINTEXT_HEADER)
    }
    private fun encrypt(context: Context, dbFile: File, rawKey: ByteArray) {
        val encrypted = File(dbFile.parentFile, "${dbFile.name}.encrypting")
        context.deleteDatabase(encrypted.name)
        val plain = SQLiteDatabase.openDatabase(
            dbFile.path,
            "",
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
            null,
            null
        )
        val version: Int
        val schemaObjects: Long
        try {
            version = plain.version
            schemaObjects = plain.countSchemaObjects()
            plain.rawExecSQL(
                "ATTACH DATABASE ? AS encrypted KEY ?",
                encrypted.path,
                String(rawKey, Charsets.US_ASCII)
            )
            plain.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            plain.rawExecSQL("PRAGMA encrypted.user_version = $version")
            plain.rawExecSQL("DETACH DATABASE encrypted")
        } finally {
            plain.close()
        }
        verifyEncryptedCopy(encrypted, rawKey, version, schemaObjects)
        context.deleteDatabase(dbFile.name)
        check(encrypted.renameTo(dbFile)) { "Unable to install the encrypted database" }
    }
    private fun verifyEncryptedCopy(file: File, rawKey: ByteArray, version: Int, schemaObjects: Long) {
        check(file.exists() && file.length() > 0) { "Encrypted copy missing" }
        check(!isPlaintext(file)) { "Copy is not encrypted" }
        val copy = SQLiteDatabase.openDatabase(
            file.path, rawKey, null, SQLiteDatabase.OPEN_READONLY, null, null
        )
        try {
            check(copy.version == version) { "user_version mismatch" }
            check(copy.countSchemaObjects() == schemaObjects) { "Schema mismatch" }
        } finally {
            copy.close()
        }
    }
    private fun SQLiteDatabase.countSchemaObjects(): Long =
        rawQuery("SELECT count(*) FROM sqlite_master", *emptyArray<Any>()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
}
