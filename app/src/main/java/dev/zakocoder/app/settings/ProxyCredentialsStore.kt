package dev.zakocoder.app.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores at most one SOCKS5 credential pair, encrypted with an Android Keystore AES/GCM key.
 *
 * The preferences file only holds the (non-secret) endpoint, a random IV and the ciphertext. The
 * plaintext payload is `{"endpoint","username","password"}` and the endpoint is also bound as GCM
 * AAD, so a renamed entry or a corrupted/truncated value fails authentication instead of being
 * used. [load] returns null on any failure (missing/corrupt data, key invalidated by a lock-screen
 * change, Keystore unavailable) and never logs the value; [save] throws only when the application
 * cannot create its own encryption key, which surfaces as a settings-save error.
 *
 * The key alias is dedicated to proxy credentials and is independent from other app secrets.
 */
class ProxyCredentialsStore(context: Context, private val alias: String = DEFAULT_ALIAS) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Returns the pair saved for exactly [endpoint], or null when absent/unreadable. */
    @Synchronized
    fun load(endpoint: String): NetworkProxy.Credentials? {
        if (endpoint.isBlank() || endpoint.length > MAX_ENDPOINT_LENGTH) return null
        if (preferences.getString(KEY_ENDPOINT, null) != endpoint) return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        val payload = preferences.getString(KEY_PAYLOAD, null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            cipher.updateAAD(endpoint.toByteArray(Charsets.UTF_8))
            val json = JSONObject(String(cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP)), Charsets.UTF_8))
            val username = json.optString("username")
            val password = json.optString("password")
            val valid = json.optString("endpoint") == endpoint &&
                username.isNotEmpty() && password.isNotEmpty() &&
                username.length <= MAX_CREDENTIAL_LENGTH && password.length <= MAX_CREDENTIAL_LENGTH &&
                username.none(Char::isISOControl) && password.none(Char::isISOControl)
            if (valid) NetworkProxy.Credentials(username, password) else null
        } catch (_: Exception) {
            // Corrupt payload, invalidated key or missing Keystore: treat as "no credentials".
            null
        }
    }

    /** Encrypts and replaces the single stored pair. */
    @Synchronized
    fun save(endpoint: String, credentials: NetworkProxy.Credentials) {
        require(endpoint.isNotBlank() && endpoint.length <= MAX_ENDPOINT_LENGTH) { "invalid proxy endpoint" }
        require(credentials.username.isNotEmpty() && credentials.password.isNotEmpty()) { "empty proxy credentials" }
        require(credentials.username.length <= MAX_CREDENTIAL_LENGTH && credentials.password.length <= MAX_CREDENTIAL_LENGTH) {
            "proxy credentials are too long"
        }
        require(!credentials.username.any(Char::isISOControl) && !credentials.password.any(Char::isISOControl)) {
            "proxy credentials contain control characters"
        }
        val payload = JSONObject()
            .put("endpoint", endpoint)
            .put("username", credentials.username)
            .put("password", credentials.password)
            .toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        cipher.updateAAD(endpoint.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        preferences.edit {
            putString(KEY_ENDPOINT, endpoint)
            putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString(KEY_PAYLOAD, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
    }

    /** Removes every stored value (also clears a corrupt entry the app can no longer decrypt). */
    @Synchronized
    fun clear() {
        preferences.edit { clear() }
    }

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private companion object {
        const val PREFERENCES = "proxy-credentials"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_IV = "iv"
        const val KEY_PAYLOAD = "payload"
        const val MAX_CREDENTIAL_LENGTH = 255
        const val MAX_ENDPOINT_LENGTH = 2048
        const val DEFAULT_ALIAS = "zakocoder-proxy"
    }
}
