package dev.zakocoder.app.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** At most one imported subscription. Implementations must never hand back partial data. */
interface ProxySubscriptionStore {
    fun load(): ProxySubscription?
    fun save(subscription: ProxySubscription)
    fun clear()
}

/**
 * Persists the imported subscription encrypted with an Android Keystore AES/GCM key.
 *
 * Security contract:
 * - the preferences file holds only a random IV and ciphertext; the source URL (which often
 *   contains a subscription token) and node credentials exist only inside the ciphertext;
 * - a dedicated key alias keeps this secret independent from proxy auth credentials and other app
 *   secrets;
 * - the constant AAD binds the payload to this schema/version, so a copied or truncated entry
 *   fails GCM authentication instead of being interpreted;
 * - [load] returns null for missing/corrupt data, an invalidated key or any schema mismatch and
 *   never throws to the UI; [save] throws only when the device cannot create/use its own key.
 *
 * Only one subscription is kept; saving replaces the previous entry. A corrupt entry that can no
 * longer be decrypted is removed by [clear] (which also fixes the "stuck" case).
 *
 * Schema v2 added kernel transports (VMess/SS/Trojan). v1 entries intentionally fail the AAD check
 * and read as null: their nodes lack transports and would all appear as "unsupported", so the user
 * re-identifies the subscription once instead of seeing a dead list.
 */
class EncryptedProxySubscriptionStore(context: Context, private val alias: String = DEFAULT_ALIAS) : ProxySubscriptionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun load(): ProxySubscription? {
        val iv = preferences.getString(KEY_IV, null) ?: return null
        val payload = preferences.getString(KEY_PAYLOAD, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            cipher.updateAAD(AAD)
            val json = JSONObject(String(cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP)), Charsets.UTF_8))
            decode(json)
        } catch (_: Exception) {
            // Corrupt payload, invalidated key or missing Keystore: behave as "no subscription".
            null
        }
    }

    @Synchronized
    override fun save(subscription: ProxySubscription) {
        require(subscription.sourceUrl.length in 1..MAX_URL_CHARS) { "invalid subscription source" }
        require(subscription.nodes.size <= MAX_NODES) { "subscription node limit exceeded" }
        val payload = encode(subscription).toString()
        // Encryption happens before touching preferences: a Keystore failure leaves the old entry.
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secret()) }
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        preferences.edit {
            putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString(KEY_PAYLOAD, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
    }

    @Synchronized
    override fun clear() {
        preferences.edit { clear() }
    }

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encode(subscription: ProxySubscription): JSONObject {
        val nodes = JSONArray()
        for (node in subscription.nodes) {
            val encoded = JSONObject().put("id", node.id).put("name", node.name).put("protocol", node.protocol.name)
                .put("host", node.host).put("port", node.port)
                .put("subscriptionPlaceholder", node.subscriptionPlaceholder)
                .apply {
                    node.username?.let { put("username", it) }
                    node.password?.let { put("password", it) }
                }
            val transport = ProxyTransportCodec.toFields(node)
            if (transport.isNotEmpty()) {
                val fields = JSONObject()
                transport.forEach { (key, value) -> fields.put(key, value) }
                encoded.put("transport", fields)
            }
            nodes.put(encoded)
        }
        return JSONObject().put(SCHEMA_KEY, SCHEMA_VERSION)
            .put("sourceUrl", subscription.sourceUrl)
            .put("fetchedAt", subscription.fetchedAt)
            .put("format", subscription.format.name)
            .put("nodes", nodes)
    }

    private fun decode(json: JSONObject): ProxySubscription? {
        if (json.optInt(SCHEMA_KEY, -1) != SCHEMA_VERSION) return null
        return decodeOrNull(json)
    }

    private fun decodeOrNull(json: JSONObject): ProxySubscription? = try {
        val sourceUrl = json.optString("sourceUrl")
        val fetchedAt = json.optLong("fetchedAt", -1)
        val format = SubscriptionFormat.entries.firstOrNull { it.name == json.optString("format") }
        val array = json.getJSONArray("nodes")
        if (sourceUrl.isEmpty() || sourceUrl.length > MAX_URL_CHARS || fetchedAt < 0 || format == null || array.length() > MAX_NODES) {
            null
        } else {
            val nodes = ArrayList<ProxyNode>(array.length())
            for (index in 0 until array.length()) {
                val node = array.optJSONObject(index)?.let(::decodeNode) ?: return null
                nodes += node
            }
            ProxySubscription(sourceUrl, fetchedAt, nodes.map { node ->
                // Old caches also need eligibility fixes. Standalone local links keep their intent.
                val subscriptionSource = sourceUrl.startsWith("https://", ignoreCase = true)
                node.copy(subscriptionPlaceholder = node.subscriptionPlaceholder ||
                    (subscriptionSource && isSubscriptionPlaceholder(node)))
            }, format)
        }
    } catch (_: Exception) {
        null
    }

    private fun decodeNode(json: JSONObject): ProxyNode? {
        val id = json.optString("id")
        val name = json.optString("name")
        val protocol = ProxyProtocol.entries.firstOrNull { it.name == json.optString("protocol") } ?: return null
        val host = json.optString("host")
        val port = json.optInt("port", -1)
        if (id.isEmpty() || id.length > MAX_ID_CHARS || name.isEmpty() || name.length > MAX_NAME_CHARS) return null
        if (host.length > MAX_HOST_CHARS || port !in 0..65535) return null
        val username = json.optString("username").takeIf { it.isNotEmpty() }
        val password = json.optString("password").takeIf { it.isNotEmpty() }
        if (username != null && (username.length > MAX_CREDENTIAL_CHARS || username.any(Char::isISOControl))) return null
        if (password != null && (password.length > MAX_CREDENTIAL_CHARS || password.any(Char::isISOControl))) return null
        // A damaged/incomplete transport must only downgrade this node to "unsupported" - the node
        // itself and the rest of the cached subscription stay intact so the user can re-fetch.
        val transport = decodeTransport(json.optJSONObject("transport"), protocol)
        val node = ProxyNode(id, name, protocol, host, port, username, password, transport,
            subscriptionPlaceholder = json.optBoolean("subscriptionPlaceholder", false))
        // Reusable nodes must still point somewhere; unsupported metadata may keep placeholders.
        if (node.supported && (host.isEmpty() || port !in 1..65535)) return null
        return node
    }

    /** Flat fields are the only persisted transport shape; ignore non-string or unknown keys. */
    private fun decodeTransport(encoded: JSONObject?, protocol: ProxyProtocol): NodeTransport? {
        if (encoded == null) return null
        return try {
            val fields = LinkedHashMap<String, String>()
            val keys = encoded.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                (encoded.opt(key) as? String)?.let { fields[key] = it }
            }
            ProxyTransportCodec.fromFields(protocol, fields)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val PREFERENCES = "proxy-subscription"
        const val KEY_IV = "iv"
        const val KEY_PAYLOAD = "payload"
        const val SCHEMA_KEY = "schemaVersion"
        const val SCHEMA_VERSION = 2
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_ALIAS = "zakocoder-proxy-subscription"
        const val MAX_NODES = 512
        const val MAX_NAME_CHARS = 80
        const val MAX_HOST_CHARS = 255
        const val MAX_CREDENTIAL_CHARS = 255
        const val MAX_URL_CHARS = 2048
        const val MAX_ID_CHARS = 64
        val AAD = "zakocoder-proxy-subscription-v2".toByteArray(Charsets.UTF_8)
    }
}
