package com.will.noteharbor.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores secrets encrypted by an Android Keystore AES key. */
class SecureSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun putString(name: String, value: String) {
        putBytes(name, value.toByteArray(StandardCharsets.UTF_8))
    }

    fun getString(name: String): String? = getBytes(name)?.toString(StandardCharsets.UTF_8)

    fun putBytes(name: String, value: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value)
        val encodedIv = Base64.encodeToString(iv, Base64.NO_WRAP)
        val encodedValue = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        preferences.edit().putString(name, "$encodedIv.$encodedValue").apply()
    }

    fun getBytes(name: String): ByteArray? {
        val stored = preferences.getString(name, null) ?: return null
        val separator = stored.indexOf('.')
        if (separator <= 0 || separator == stored.lastIndex) return null
        return runCatching {
            val iv = Base64.decode(stored.substring(0, separator), Base64.DEFAULT)
            val encrypted = Base64.decode(stored.substring(separator + 1), Base64.DEFAULT)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(encrypted)
        }.getOrNull()
    }

    fun remove(name: String) {
        preferences.edit().remove(name).apply()
    }

    fun contains(name: String): Boolean = preferences.contains(name)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "noteharbor.secret-store.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PREFERENCES_NAME = "noteharbor.secure-secrets"
    }
}
