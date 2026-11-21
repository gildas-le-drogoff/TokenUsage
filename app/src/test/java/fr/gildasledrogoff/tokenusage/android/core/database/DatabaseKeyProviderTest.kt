package fr.gildasledrogoff.tokenusage.core.database

import fr.gildasledrogoff.tokenusage.core.security.InMemorySharedPreferences
import fr.gildasledrogoff.tokenusage.core.security.SecureStorageFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DatabaseKeyProviderTest {
    private lateinit var prefs: InMemorySharedPreferences
    private lateinit var provider: DatabaseKeyProvider

    @Before
    fun setup() {
        prefs = InMemorySharedPreferences()
        val factory = mock(SecureStorageFactory::class.java)
        `when`(factory.databaseKeys).thenReturn(prefs)
        provider = DatabaseKeyProvider(factory)
    }

    @Test
    fun firstCallCreatesNewKey() {
        assertTrue(provider.getOrCreate().isNew)
    }

    @Test
    fun keyIsFormattedAsSqlcipherRawHexLiteral() {
        val text = String(provider.getOrCreate().rawKey, Charsets.US_ASCII)
        assertTrue(Regex("x'[0-9a-f]{64}'").matches(text))
    }

    @Test
    fun secondCallReturnsSameKeyNotNew() {
        val first = provider.getOrCreate()
        val second = provider.getOrCreate()
        assertFalse(second.isNew)
        assertArrayEquals(first.rawKey, second.rawKey)
    }

    @Test
    fun malformedStoredKeyIsReplaced() {
        prefs.edit().putString("sqlcipher_key", "abcd").commit()
        val key = provider.getOrCreate()
        assertTrue(key.isNew)
        assertEquals(64, prefs.getString("sqlcipher_key", null)?.length)
    }

    @Test
    fun differentStoresGenerateDifferentKeys() {
        val other = DatabaseKeyProvider(
            mock(SecureStorageFactory::class.java).also {
                `when`(it.databaseKeys).thenReturn(InMemorySharedPreferences())
            }
        )
        assertNotEquals(
            String(provider.getOrCreate().rawKey),
            String(other.getOrCreate().rawKey)
        )
    }

    @Test
    fun failedPersistenceThrowsInsteadOfReturningAnUnsavedKey() {
        val editor = mock(android.content.SharedPreferences.Editor::class.java)
        val failing = mock(android.content.SharedPreferences::class.java)
        `when`(failing.getString("sqlcipher_key", null)).thenReturn(null)
        `when`(failing.edit()).thenReturn(editor)
        `when`(editor.putString(eq("sqlcipher_key"), anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(false)
        val factory = mock(SecureStorageFactory::class.java)
        `when`(factory.databaseKeys).thenReturn(failing)

        val error = runCatching { DatabaseKeyProvider(factory).getOrCreate() }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    @Test
    fun storedKeyOfExpectedLengthIsReusedVerbatim() {
        val hex = "ab".repeat(32)
        prefs.edit().putString("sqlcipher_key", hex).commit()

        val key = provider.getOrCreate()

        assertFalse(key.isNew)
        assertEquals("x'$hex'", String(key.rawKey, Charsets.US_ASCII))
    }
}
