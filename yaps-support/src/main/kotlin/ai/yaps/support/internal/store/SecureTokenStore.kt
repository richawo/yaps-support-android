package ai.yaps.support.internal.store

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ai.yaps.support.internal.core.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Holds the device token (PROTOCOL §3.1), encrypted with an AES-256-GCM key that never leaves the
 * Android Keystore, and stored as `base64(iv ‖ ciphertext)` in a private SharedPreferences file.
 *
 * If the Keystore is unusable (rare OEM bugs, or the key was invalidated), the token lives in memory
 * only and the device registers again next launch: that costs a new registration, never a leak.
 */
internal class SecureTokenStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Volatile private var cached: String? = null
    @Volatile private var loaded = false

    fun get(): String? {
        if (loaded) return cached
        synchronized(this) {
            if (!loaded) {
                cached = prefs.getString(KEY_TOKEN, null)?.let(::decrypt)
                loaded = true
            }
        }
        return cached
    }

    fun set(token: String) {
        synchronized(this) {
            cached = token
            loaded = true
            val encrypted = encrypt(token)
            prefs.edit().apply { if (encrypted != null) putString(KEY_TOKEN, encrypted) else remove(KEY_TOKEN) }.apply()
        }
    }

    fun clear() {
        synchronized(this) {
            cached = null
            loaded = true
            prefs.edit().remove(KEY_TOKEN).apply()
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + sealed, Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w("Keystore unavailable; the device token will be kept in memory only", e)
        null
    }

    private fun decrypt(stored: String): String? = try {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val ivLength = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(bytes, 1 + ivLength, bytes.size - 1 - ivLength), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w("Stored device token could not be decrypted; registering again", e)
        prefs.edit().remove(KEY_TOKEN).apply()
        null
    }

    private companion object {
        const val PREFS = "ai.yaps.support.secure"
        const val KEY_TOKEN = "device_token"
        const val ALIAS = "ai.yaps.support.device_token"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
