package app.foodfeed.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the sign-in token encrypted on the phone with a key that lives in the Android
 * Keystore (it can't be copied off the device). Where the Keystore isn't available, the
 * token is kept as it was before.
 */
object TokenCipher {
    private const val ALIAS = "worthabite_token_v1"
    private const val PREFIX = "enc1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** The token sealed for storage, or as it is if the Keystore can't be used. */
    fun seal(token: String): String = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = c.iv + c.doFinal(token.toByteArray())
        PREFIX + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }.getOrDefault(token)

    /** The stored value back as a token; null if it was sealed and can't be opened any more. */
    fun open(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return stored // from before encryption
        return runCatching {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(c.doFinal(bytes, 12, bytes.size - 12))
        }.getOrNull()
    }

    fun isSealed(stored: String) = stored.startsWith(PREFIX)
}
