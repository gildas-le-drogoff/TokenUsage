package fr.gildasledrogoff.tokenusage.core.security

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class SecureStorageFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val integrityGuard: IntegrityGuard
) {
    private val vault: TinkKeysetVault by lazy { openVaultOrReset() }

    
    val credentials: SharedPreferences by lazy {
        open(CREDENTIALS_STORE).also { store ->
            if (store is SecurePreferences) LegacyEncryptedPrefsMigrator(context).migrateInto(store)
        }
    }

    
    val databaseKeys: SharedPreferences by lazy { open(DATABASE_KEY_STORE) }

    private fun open(storeName: String): SharedPreferences {
        if (integrityGuard.isVaultLocked) return LockedPreferences
        return SecurePreferences(
            storeName = storeName,
            backing = context.getSharedPreferences(storeName, Context.MODE_PRIVATE),
            valueAead = vault.valueAead,
            keyDaead = vault.keyDaead
        )
    }

    private fun openVaultOrReset(): TinkKeysetVault =
        try {
            newVault().also { it.valueAead; it.keyDaead }
        } catch (_: GeneralSecurityException) {
            wipeEverything()
            newVault()
        } catch (_: ProviderException) {
            wipeEverything()
            newVault()
        }

    private fun newVault() = TinkKeysetVault(
        storage = context.getSharedPreferences(TinkKeysetVault.KEYSET_FILE, Context.MODE_PRIVATE),
        masterKey = KeystoreMasterKey(context)
    )

    private fun wipeEverything() {
        KeystoreMasterKey(context).destroy()
        listOf(TinkKeysetVault.KEYSET_FILE, CREDENTIALS_STORE, DATABASE_KEY_STORE)
            .forEach(context::deleteSharedPreferences)
    }

    companion object {
        const val CREDENTIALS_STORE = "tokenusage_secure_store_v2"
        const val DATABASE_KEY_STORE = "tokenusage_db_key_store"
    }
}
