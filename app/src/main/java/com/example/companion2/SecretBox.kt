package com.example.companion2

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Seals secrets (your Gemini key, Orbit Desk's pairing secret) before they're saved. The sealing
// key lives in Android's Keystore: it's made on this phone, stays in its secure hardware where
// the phone has it, and can't be copied out, even by Orbit. So a copy of Orbit's saved settings
// alone is useless without this exact phone.
object SecretBox {
    private const val ALIAS = "orbit_secrets"
    private const val PREFIX = "v1:"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    // "v1:<iv>:<sealed>", or null if this phone's Keystore isn't working
    fun seal(plain: String): String? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w("Orbit", "Keystore unavailable; keeping this secret the old way", e)
        null
    }

    // The secret, or null if it can't be opened (made on another phone, or the Keystore was reset)
    fun open(sealed: String): String? = try {
        val parts = sealed.removePrefix(PREFIX).split(":")
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val data = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(data), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w("Orbit", "Couldn't open a sealed secret", e)
        null
    }
}
