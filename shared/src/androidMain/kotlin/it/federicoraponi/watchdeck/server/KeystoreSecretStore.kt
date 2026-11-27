package it.federicoraponi.watchdeck.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEY_ALIAS = "watchdeck.secrets"
private const val KEYSTORE = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val TAG_BITS = 128

/** Values encrypted with an AES key that never leaves the Android Keystore. */
class KeystoreSecretStore(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SecretStore {
    private val preferences by lazy { context.getSharedPreferences("secrets", Context.MODE_PRIVATE) }

    override suspend fun read(key: String): String? =
        withContext(dispatcher) {
            val stored = preferences.getString(key, null) ?: return@withContext null
            try {
                decrypt(key, stored)
            } catch (_: GeneralSecurityException) {
                // The key is gone or the data is damaged: the user has to enter the password again.
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    override suspend fun write(
        key: String,
        value: String?,
    ) = withContext(dispatcher) {
        preferences.edit(commit = true) {
            if (value == null) remove(key) else putString(key, encrypt(key, value))
        }
    }

    // The entry name is authenticated, so an encrypted value cannot be moved to another entry.
    private fun encrypt(
        name: String,
        value: String,
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(name.encodeToByteArray())
        val encrypted = cipher.doFinal(value.encodeToByteArray())
        return "${encode(cipher.iv)}.${encode(encrypted)}"
    }

    private fun decrypt(
        name: String,
        stored: String,
    ): String {
        val (iv, encrypted) = stored.split('.', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(name.encodeToByteArray())
        return cipher.doFinal(encrypted).decodeToString()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
