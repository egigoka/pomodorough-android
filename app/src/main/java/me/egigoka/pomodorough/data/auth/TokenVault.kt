package me.egigoka.pomodorough.data.auth

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.TokenPair

interface TokenStore {
    fun read(): TokenPair?
    fun write(tokens: TokenPair)
    fun clear()
}

class TokenVault(
    context: Context,
    private val json: Json,
) : TokenStore {
    private val preferences = context.getSharedPreferences("pomodorough_tokens", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    override fun read(): TokenPair? {
        val encoded = preferences.getString(PayloadKey, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > IvSize)
            val iv = bytes.copyOfRange(0, IvSize)
            val ciphertext = bytes.copyOfRange(IvSize, bytes.size)
            val cipher = Cipher.getInstance(Transformation).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TagSizeBits, iv))
            }
            json.decodeFromString<TokenPair>(cipher.doFinal(ciphertext).decodeToString())
        }.getOrElse {
            clear()
            null
        }
    }

    @Synchronized
    @SuppressLint("ApplySharedPref")
    override fun write(tokens: TokenPair) {
        val cipher = Cipher.getInstance(Transformation).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        val encrypted = cipher.doFinal(json.encodeToString(tokens).encodeToByteArray())
        val payload = cipher.iv + encrypted
        check(
            preferences.edit()
                .putString(PayloadKey, Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit(),
        ) { "Could not persist authentication tokens" }
    }

    @Synchronized
    @SuppressLint("ApplySharedPref")
    override fun clear() {
        check(preferences.edit().remove(PayloadKey).commit()) {
            "Could not clear authentication tokens"
        }
    }

    private fun secretKey(): SecretKey {
        (keyStore.getKey(KeyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KeyAlias = "pomodorough-token-key-v1"
        const val PayloadKey = "token-pair"
        const val Transformation = "AES/GCM/NoPadding"
        const val IvSize = 12
        const val TagSizeBits = 128
    }
}
