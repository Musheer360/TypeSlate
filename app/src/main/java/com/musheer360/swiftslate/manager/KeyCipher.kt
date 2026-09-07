package com.musheer360.swiftslate.manager

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypt/decrypt seam for the stored API-key blob.
 *
 * Extracted from [KeyManager] so its storage, caching and rotation logic can actually be tested:
 * AndroidKeyStore's AES-GCM provider is HAL-backed and not implemented under Robolectric, so 11
 * of KeyManagerTest's 17 cases used to end in an `Assume` skip. The suite reported green while
 * the security-critical path was never exercised.
 */
internal interface KeyCipher {
    /** False when the backing key could not be created or loaded. */
    val available: Boolean

    /** @throws Exception when the key is unavailable or encryption fails. */
    fun encrypt(plainText: String): String

    /** Null when [encrypted] is not decryptable: wrong key, corrupt, or not this format. */
    fun decrypt(encrypted: String): String?
}

/** AES-256-GCM through a non-exportable AndroidKeyStore key. */
internal class AndroidKeystoreCipher : KeyCipher {

    private companion object {
        const val KEY_ALIAS = "typeslate_secure_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        /**
         * Separates the base64 IV from the base64 ciphertext. Safe because base64 (NO_WRAP)
         * emits only A-Za-z0-9+/= — see [KeyManager] for why detecting the *legacy* plaintext
         * format by the absence of this character was not safe.
         */
        const val IV_SEPARATOR = "]"
    }

    /** Resolved once: this used to reload the whole KeyStore on every encrypt and decrypt. */
    @Volatile
    private var cachedSecretKey: SecretKey? = null

    override var available: Boolean = true
        private set

    init {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                keyGenerator.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .apply {
                            // Keys are only ever needed while the user is actively typing, so
                            // requiring an unlocked device costs nothing and stops the stored
                            // API keys from being decryptable on a locked device. Applies to
                            // newly generated keys only; existing installs keep theirs.
                            //
                            // Gated to Android 15+ on Google's own advice (issue #141). Android
                            // 12, 13 and 14 ship a platform bug where keys carrying this flag
                            // cannot be generated, imported or used at all unless a secure lock
                            // screen is set, and are silently deleted if the user later removes
                            // it. Both failures are terminal here: generation throws, so
                            // [available] goes false and no key can ever be saved; and deletion
                            // makes the stored blob undecryptable, so getKeys() returns empty
                            // and the user's keys are simply gone. Enabling it below API 35
                            // trades a locked-device hardening win for total key loss on three
                            // major versions.
                            //
                            // See KeyGenParameterSpec.Builder#setUnlockedDeviceRequired.
                            if (android.os.Build.VERSION.SDK_INT >=
                                android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM
                            ) {
                                setUnlockedDeviceRequired(true)
                            }
                        }
                        .build()
                )
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            android.util.Log.e("KeyCipher", "Keystore init failed", e)
            available = false
        }
    }

    private fun secretKey(): SecretKey? {
        cachedSecretKey?.let { return it }
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.also { cachedSecretKey = it }
        } catch (e: Exception) {
            android.util.Log.e("KeyCipher", "Failed to get secret key", e)
            null
        }
    }

    override fun encrypt(plainText: String): String {
        val key = secretKey() ?: throw IllegalStateException("Keystore unavailable")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(
            cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        return "$iv$IV_SEPARATOR$body"
    }

    override fun decrypt(encrypted: String): String? {
        val parts = encrypted.split(IV_SEPARATOR)
        if (parts.size != 2) return null
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val body = Base64.decode(parts[1], Base64.NO_WRAP)
            val key = secretKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
