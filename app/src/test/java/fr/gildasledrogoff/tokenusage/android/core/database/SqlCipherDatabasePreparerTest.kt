package fr.gildasledrogoff.tokenusage.core.database

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
class SqlCipherDatabasePreparerTest {
    private lateinit var context: Context
    private val name = "prepared.db"
    private val encryptedBytes = ByteArray(64) { (it * 7 + 3).toByte() }
    private val plaintextHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    private val dbFile: File get() = context.getDatabasePath(name)

    private fun key(isNew: Boolean) =
        DatabaseKeyProvider.DatabaseKey("x'00'".toByteArray(Charsets.US_ASCII), isNew)

    private fun writeDb(bytes: ByteArray) {
        dbFile.parentFile!!.mkdirs()
        dbFile.writeBytes(bytes)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `a missing database is left alone`() {
        SqlCipherDatabasePreparer.prepare(context, name, key(isNew = true))

        assertFalse(dbFile.exists())
    }

    @Test
    fun `an encrypted database with a known key is kept`() {
        writeDb(encryptedBytes)

        SqlCipherDatabasePreparer.prepare(context, name, key(isNew = false))

        assertArrayEquals(encryptedBytes, dbFile.readBytes())
    }

    @Test
    fun `an encrypted database with a fresh key is unreadable and deleted`() {
        writeDb(encryptedBytes)

        SqlCipherDatabasePreparer.prepare(context, name, key(isNew = true))

        assertFalse(dbFile.exists())
    }

    @Test
    fun `a file shorter than the sqlite header is not treated as plaintext`() {
        writeDb(plaintextHeader.copyOf(4))

        SqlCipherDatabasePreparer.prepare(context, name, key(isNew = false))

        assertTrue(dbFile.exists())
    }

    @Test
    fun `a plaintext database is never left behind in clear`() {
        writeDb(plaintextHeader + ByteArray(100))

        SqlCipherDatabasePreparer.prepare(context, name, key(isNew = false))

        val stillPlaintext = dbFile.exists() &&
            dbFile.readBytes().copyOf(plaintextHeader.size).contentEquals(plaintextHeader)
        assertFalse(stillPlaintext)
    }
}
