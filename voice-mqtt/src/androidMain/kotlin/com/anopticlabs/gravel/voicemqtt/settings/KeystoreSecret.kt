package com.anopticlabs.gravel.voicemqtt.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import co.touchlab.kermit.Logger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val DEFAULT_ALIAS = "gravel_voice_mqtt_secret"

/**
 * Seals the broker password with a key that lives in the Android Keystore (AES/GCM/256, generated on first
 * use, never exportable from the keystore and never written to disk in the clear).
 *
 * The same job `EncryptedSharedPreferences` does, without pulling in `androidx.security-crypto`: it is
 * deprecated upstream, and this is a dozen lines around the platform's own Keystore. The sealed value is
 * `base64(iv):base64(ciphertext)` in a private SharedPreferences file; a keystore that has been invalidated
 * makes [open] return null, and the user is asked for the password again rather than being told a lie.
 */
class KeystoreSecret(private val alias: String = DEFAULT_ALIAS) {

    fun seal(plain: String): String? {
        if (plain.isEmpty()) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(sealed, Base64.NO_WRAP)}"
        } catch (e: Exception) {
            logger.w { "broker password could not be sealed: ${e::class.java.simpleName}" }
            null
        }
    }

    fun open(sealed: String?): String? {
        if (sealed.isNullOrEmpty()) return null
        val parts = sealed.split(':')
        if (parts.size != 2) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            logger.w { "broker password could not be read: ${e::class.java.simpleName}" }
            null
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        val logger = Logger.withTag("KeystoreSecret")
        const val STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
