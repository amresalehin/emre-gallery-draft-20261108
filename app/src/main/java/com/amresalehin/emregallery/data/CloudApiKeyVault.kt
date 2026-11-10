package com.amresalehin.emregallery.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores API credentials encrypted with an AES-GCM key protected by Android Keystore.
 * Legacy plaintext values are read once and rewritten in encrypted form.
 */
internal object CloudApiKeyVault {
    private const val KEY_ALIAS = "emre_gallery_cloud_api_key_v1"
    private const val PREFIX = "emre-aesgcm-v1:"
    private const val IV_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encryptForStorage(value: String): String {
        if (value.isBlank() || isEncrypted(value)) return value
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    fun decryptIfNeeded(value: String): String {
        if (value.isBlank() || !isEncrypted(value)) return value
        return runCatching {
            val combined = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            require(combined.size > IV_SIZE_BYTES) { "Invalid encrypted cloud API key" }
            val iv = combined.copyOfRange(0, IV_SIZE_BYTES)
            val ciphertext = combined.copyOfRange(IV_SIZE_BYTES, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_SIZE_BITS, iv)
            )
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrDefault("")
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
