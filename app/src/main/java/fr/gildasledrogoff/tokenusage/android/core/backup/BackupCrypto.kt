package fr.gildasledrogoff.tokenusage.core.backup

import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec


class BackupCryptoException(message: String) : Exception(message)


object BackupCrypto {


    const val MIN_PASSWORD_LENGTH = 6

    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private val MAGIC = "CBX1".toByteArray(Charsets.US_ASCII)
    private val HEADER_LENGTH = MAGIC.size + SALT_LENGTH + IV_LENGTH

    private val random = SecureRandom()

    fun isValidPassword(password: String): Boolean =
        normalize(password).let { it.codePointCount(0, it.length) >= MIN_PASSWORD_LENGTH }

    fun isUsableForImport(password: String): Boolean = password.isNotEmpty()

     private fun normalize(password: String): String = Normalizer.normalize(password, Normalizer.Form.NFC)

    fun encrypt(plain: ByteArray, pin: String): ByteArray {
        val salt = randomBytes(SALT_LENGTH)
        val iv = randomBytes(IV_LENGTH)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(pin, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plain)

        return ByteArrayOutputStream(MAGIC.size + salt.size + iv.size + ciphertext.size).use { out ->
            out.write(MAGIC)
            out.write(salt)
            out.write(iv)
            out.write(ciphertext)
            out.toByteArray()
        }
    }


    fun decrypt(container: ByteArray, pin: String): ByteArray {
        if (container.size <= HEADER_LENGTH) {
            throw BackupCryptoException("Corrupted backup: too short")
        }
        if (!container.startsWith(MAGIC)) {
            throw BackupCryptoException("Not a TokenUsage backup")
        }

        val salt = container.copyOfRange(MAGIC.size, MAGIC.size + SALT_LENGTH)
        val iv = container.copyOfRange(MAGIC.size + SALT_LENGTH, HEADER_LENGTH)
        val ciphertext = container.copyOfRange(HEADER_LENGTH, container.size)

        val candidates = listOf(pin, pin.trim()).distinct()
        for (candidate in candidates) {
            tryDecrypt(candidate, salt, iv, ciphertext)?.let { return it }
        }
        throw BackupCryptoException("Wrong password or corrupted backup")
    }

    private fun tryDecrypt(password: String, salt: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray? {
        if (password.isEmpty()) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        return try {
            cipher.doFinal(ciphertext)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream(bytes.size / 2)
            val buffer = ByteArray(8 * 1024)
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer))
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    fun inflate(bytes: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 2)
            val buffer = ByteArray(8 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                // Un flux vide se termine avec n == 0 : seul un flux non terminé qui réclame
                // des données est tronqué.
                if (n == 0 && !inflater.finished() && inflater.needsInput()) {
                    throw BackupCryptoException("Corrupted backup: truncated data")
                }
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } catch (_: DataFormatException) {
            throw BackupCryptoException("Corrupted backup: invalid data")
        } finally {
            inflater.end()
        }
    }

    private fun deriveKey(pin: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(normalize(pin).toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun randomBytes(length: Int): ByteArray =
        ByteArray(length).also { random.nextBytes(it) }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)
}
