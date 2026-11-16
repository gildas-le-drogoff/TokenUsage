package fr.gildasledrogoff.tokenusage.core.security

import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.daead.DeterministicAeadConfig
import com.google.crypto.tink.daead.PredefinedDeterministicAeadParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SecurePreferencesTest {

    private lateinit var backing: InMemorySharedPreferences
    private lateinit var aead: Aead
    private lateinit var daead: DeterministicAead
    private lateinit var prefs: SecurePreferences

    @Before
    fun setUp() {
        AeadConfig.register()
        DeterministicAeadConfig.register()
        aead = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        daead = KeysetHandle.generateNew(PredefinedDeterministicAeadParameters.AES256_SIV)
            .getPrimitive(RegistryConfiguration.get(), DeterministicAead::class.java)
        backing = InMemorySharedPreferences()
        prefs = SecurePreferences("store", backing, aead, daead)
    }

    @Test
    fun `round-trips typed values`() {
        prefs.edit()
            .putString("CLAUDE_access_token", "sk-ant-sid02-secret")
            .putLong("CLAUDE_expires_at", 1_700_000_000L)
            .putBoolean("notifications_enabled", false)
            .putStringSet("set", setOf("a", "b"))
            .commit()

        assertEquals("sk-ant-sid02-secret", prefs.getString("CLAUDE_access_token", null))
        assertEquals(1_700_000_000L, prefs.getLong("CLAUDE_expires_at", -1))
        assertFalse(prefs.getBoolean("notifications_enabled", true))
        assertEquals(setOf("a", "b"), prefs.getStringSet("set", null))
    }

    @Test
    fun `stores neither key names nor values in clear`() {
        prefs.edit().putString("CLAUDE_access_token", "sk-ant-sid02-secret").commit()

        val raw = backing.data.entries.joinToString { "${it.key}=${it.value}" }
        assertFalse(raw.contains("CLAUDE"))
        assertFalse(raw.contains("secret"))
    }

    @Test
    fun `value moved to another key fails authentication`() {
        prefs.edit().putString("a", "secret-a").putString("b", "secret-b").commit()
        val (keyA, keyB) = backing.data.keys.toList()
        backing.data[keyB] = backing.data[keyA]

        val readable = listOf(prefs.getString("a", null), prefs.getString("b", null))
        assertEquals(1, readable.count { it != null })
    }

    @Test
    fun `another store name cannot read the same backing file`() {
        prefs.edit().putString("token", "secret").commit()
        val other = SecurePreferences("other", backing, aead, daead)

        assertNull(other.getString("token", null))
        assertTrue(other.all.isEmpty())
    }

    @Test
    fun `getAll decrypts keys used for prefix filtering`() {
        prefs.edit().putString("ZAI_accounts", "[]").putLong("CLAUDE_x_resets_at", 5L).commit()

        assertEquals(mapOf("ZAI_accounts" to "[]", "CLAUDE_x_resets_at" to 5L), prefs.all)
    }

    @Test
    fun `clear is applied before staged puts`() {
        prefs.edit().putString("old", "1").commit()
        prefs.edit().putString("new", "2").clear().commit()

        assertNull(prefs.getString("old", null))
        assertEquals("2", prefs.getString("new", null))
    }

    @Test
    fun `remove and null put delete the entry`() {
        prefs.edit().putString("a", "1").putString("b", "2").commit()
        prefs.edit().remove("a").putString("b", null).commit()

        assertFalse(prefs.contains("a"))
        assertFalse(prefs.contains("b"))
        assertTrue(backing.data.isEmpty())
    }
}
