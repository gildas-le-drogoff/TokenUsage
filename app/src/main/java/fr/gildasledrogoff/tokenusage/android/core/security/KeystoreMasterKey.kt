package fr.gildasledrogoff.tokenusage.core.security

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec


internal class KeystoreMasterKey(
    private val context: Context,
    private val alias: String = DEFAULT_ALIAS
) : Aead {

    private val key: SecretKey by lazy { loadOrCreate() }

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray =
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            if (associatedData != null && associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            if (iv.size != IV_LENGTH) throw GeneralSecurityException("Unexpected IV length ${iv.size}")
            iv + ciphertext
        } catch (e: ProviderException) {
            throw GeneralSecurityException("Keystore encryption failed", e)
        }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray {
        if (ciphertext.size < IV_LENGTH + TAG_LENGTH_BYTES) {
            throw GeneralSecurityException("Ciphertext too short")
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_LENGTH_BYTES * 8, ciphertext, 0, IV_LENGTH)
            )
            if (associatedData != null && associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
            cipher.doFinal(ciphertext, IV_LENGTH, ciphertext.size - IV_LENGTH)
        } catch (e: ProviderException) {
            throw GeneralSecurityException("Keystore decryption failed", e)
        }
    }

    
    fun destroy() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun loadOrCreate(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val wantsStrongBox =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (wantsStrongBox) {
            try {
                val strongBoxKey = generate(strongBox = true)
                if (selfTest(strongBoxKey)) return strongBoxKey
            } catch (_: StrongBoxUnavailableException) {
            } catch (_: ProviderException) {
                
            }
            keyStore.deleteEntry(alias)
        }

        val teeKey = generate(strongBox = false)
        if (!selfTest(teeKey)) {
            keyStore.deleteEntry(alias)
            throw GeneralSecurityException("AndroidKeyStore AES-GCM self-test failed")
        }
        return teeKey
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    
    private fun selfTest(candidate: SecretKey): Boolean = try {
        val message = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val aad = SELF_TEST_AAD
        val enc = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, candidate)
            updateAAD(aad)
        }
        val ct = enc.doFinal(message)
        val dec = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, candidate, GCMParameterSpec(TAG_LENGTH_BYTES * 8, enc.iv))
            updateAAD(aad)
        }
        dec.doFinal(ct).contentEquals(message)
    } catch (_: GeneralSecurityException) {
        false
    } catch (_: ProviderException) {
        false
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val DEFAULT_ALIAS = "tokenusage_master_key_v2"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BYTES = 16
        private val SELF_TEST_AAD = "tokenusage-selftest".toByteArray(Charsets.UTF_8)
    }
}
