package fr.gildasledrogoff.tokenusage.core.security

import android.content.SharedPreferences
import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.Parameters
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.daead.DeterministicAeadConfig
import com.google.crypto.tink.daead.PredefinedDeterministicAeadParameters
import java.util.Base64

internal class TinkKeysetVault(
    private val storage: SharedPreferences,
    private val masterKey: Aead
) {
    init {
        AeadConfig.register()
        DeterministicAeadConfig.register()
    }

    val valueAead: Aead by lazy {
        loadOrCreate(VALUE_KEYSET, PredefinedAeadParameters.AES256_GCM)
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }
    val keyDaead: DeterministicAead by lazy {
        loadOrCreate(KEY_KEYSET, PredefinedDeterministicAeadParameters.AES256_SIV)
            .getPrimitive(RegistryConfiguration.get(), DeterministicAead::class.java)
    }

    @Synchronized
    private fun loadOrCreate(name: String, parameters: Parameters): KeysetHandle {
        val associatedData = "$KEYSET_AAD_PREFIX$name".toByteArray(Charsets.UTF_8)
        storage.getString(name, null)?.let { stored ->
            return TinkProtoKeysetFormat.parseEncryptedKeyset(
                Base64.getDecoder().decode(stored),
                masterKey,
                associatedData,
                RegistryConfiguration.get()
            )
        }
        val handle = KeysetHandle.generateNew(parameters, RegistryConfiguration.get())
        val wrapped = TinkProtoKeysetFormat.serializeEncryptedKeyset(
            handle,
            masterKey,
            associatedData,
            RegistryConfiguration.get()
        )
        check(
            storage.edit().putString(name, Base64.getEncoder().encodeToString(wrapped)).commit()
        ) {
            "Unable to persist keyset $name"
        }
        return handle
    }

    companion object {
        const val KEYSET_FILE = "tokenusage_keysets"
        private const val VALUE_KEYSET = "values_aes256_gcm"
        private const val KEY_KEYSET = "keys_aes256_siv"
        private const val KEYSET_AAD_PREFIX = "tokenusage-keyset:"
    }
}
