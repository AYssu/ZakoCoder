package dev.zakocoder.app.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

data class AiConfig(val endpoint: String = "", val model: String = "", val key: String = "", val parameters: AiParameters = AiParameters()) {
    override fun toString() = "AiConfig(model=$model, key=<redacted>)"
}

/** Blocking IO. v1 is retained for rollback; v2 never stores plaintext credentials. */
class AiConfigStore(context: Context, name: String = "ai-config-v1", private val alias: String = "zakocoder-ai-v1") {
    private val file = AtomicFile(File(context.filesDir, "$name.json"))
    private val catalogFile = AtomicFile(File(context.filesDir, "$name-profiles-v2.json"))
    companion object {
        fun endpoint(value: String): String {
            val base = value.trim().trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/models")
            val uri = URI(base)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535))
            require(base.length <= 2048)
            return base
        }
        private fun origin(value: String): String { val uri = URI(endpoint(value)); return "${uri.host.lowercase()}:${if (uri.port == -1) 443 else uri.port}" }
    }
    private fun exists(file: AtomicFile) = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    private fun read(file: AtomicFile): JSONObject {
        val bytes = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 1024 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun decrypt(json: JSONObject, id: String? = null): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(json.getString("iv"), Base64.NO_WRAP)))
        if (id != null) cipher.updateAAD(id.toByteArray())
        return cipher.doFinal(Base64.decode(json.getString("key"), Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    private fun encrypt(json: JSONObject, key: String, id: String? = null): JSONObject {
        require(key.length <= 8192 && !key.any(Char::isISOControl))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        if (id != null) cipher.updateAAD(id.toByteArray())
        return json.put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).put("key", Base64.encodeToString(cipher.doFinal(key.toByteArray()), Base64.NO_WRAP))
    }
    private fun parameters(json: JSONObject): AiParameters = AiParameters(
        if (json.has("maxTokens")) json.getInt("maxTokens") else null,
        if (json.has("temperature")) json.getDouble("temperature") else null,
        if (json.has("topP")) json.getDouble("topP") else null).also { it.validate() }
    private fun summary(json: JSONObject) = AiProfile(json.getString("id"), json.getString("name"), endpoint(json.getString("endpoint")), json.getString("model"), json.getBoolean("hasKey"), parameters(json))
    private fun rawCatalog(): JSONObject {
        if (exists(catalogFile)) return read(catalogFile).also { json ->
            require(json.getInt("schemaVersion") == 2)
            val profiles = json.getJSONArray("profiles"); require(profiles.length() <= 32)
            val ids = (0 until profiles.length()).map { summary(profiles.getJSONObject(it)).id }
            require(ids.distinct().size == ids.size)
            require(json.isNull("activeId") && ids.isEmpty() || json.getString("activeId") in ids)
        }
        val profiles = JSONArray()
        if (exists(file)) {
            val old = read(file)
            // Reading legacy metadata does not return its credential to a UI state.
            val key = decrypt(old)
            profiles.put(encrypt(JSONObject().put("id", "legacy").put("name", old.getString("model"))
                .put("endpoint", old.getString("endpoint")).put("model", old.getString("model")).put("hasKey", key.isNotEmpty()), key, "legacy"))
        }
        return JSONObject().put("schemaVersion", 2).put("activeId", if (profiles.length() == 0) JSONObject.NULL else "legacy").put("profiles", profiles)
    }
    private fun write(target: AtomicFile, json: JSONObject) {
        val stream = target.startWrite()
        try { stream.write(json.toString().toByteArray()); target.finishWrite(stream) }
        catch (error: Exception) { target.failWrite(stream); throw error }
    }
    @Synchronized fun catalog(): AiCatalog {
        val json = rawCatalog(); val profiles = json.getJSONArray("profiles")
        return AiCatalog(json.optString("activeId").takeUnless { it == "null" || it.isEmpty() }, (0 until profiles.length()).map { summary(profiles.getJSONObject(it)) })
    }
    @Synchronized fun load(): AiConfig {
        if (!exists(catalogFile)) {
            if (!exists(file)) return AiConfig()
            val json = read(file)
            return AiConfig(endpoint(json.getString("endpoint")), json.getString("model"), decrypt(json))
        }
        val json = rawCatalog(); val profiles = json.getJSONArray("profiles"); val id = json.optString("activeId")
        val profile = (0 until profiles.length()).map { profiles.getJSONObject(it) }.singleOrNull { it.getString("id") == id } ?: return AiConfig()
        return AiConfig(profile.getString("endpoint"), profile.getString("model"), decrypt(profile, id), parameters(profile))
    }
    @Synchronized fun resolve(draft: AiModelDraft, requireModel: Boolean = true): AiConfig {
        val normalized = endpoint(draft.endpoint)
        require(!requireModel || draft.model.isNotBlank())
        require(draft.model.length <= 200 && !draft.model.any(Char::isISOControl))
        draft.parameters.validate()
        val profiles = rawCatalog().getJSONArray("profiles")
        val old = (0 until profiles.length()).map { profiles.getJSONObject(it) }.singleOrNull { it.getString("id") == draft.id }
        require(draft.id == null || old != null)
        val key = if (draft.replacementKey != null) draft.replacementKey else if (old == null) "" else {
            val previous = decrypt(old, old.getString("id"))
            if (previous.isNotEmpty() && origin(old.getString("endpoint")) != origin(normalized)) throw AiOriginChanged()
            previous
        }
        require(key.length <= 8192 && !key.any(Char::isISOControl))
        return AiConfig(normalized, draft.model.trim(), key, draft.parameters)
    }
    @Synchronized fun saveProfile(draft: AiModelDraft) {
        require(draft.name.isNotBlank() && draft.name.length <= 80 && !draft.name.any(Char::isISOControl))
        val resolved = resolve(draft)
        val json = rawCatalog(); val profiles = json.getJSONArray("profiles")
        require(draft.id != null || profiles.length() < 32)
        val id = draft.id ?: UUID.randomUUID().toString()
        val item = encrypt(JSONObject().put("id", id).put("name", draft.name.trim()).put("endpoint", resolved.endpoint)
            .put("model", resolved.model).put("hasKey", resolved.key.isNotEmpty()), resolved.key, id)
        resolved.parameters.maxTokens?.let { item.put("maxTokens", it) }; resolved.parameters.temperature?.let { item.put("temperature", it) }; resolved.parameters.topP?.let { item.put("topP", it) }
        val index = (0 until profiles.length()).singleOrNull { profiles.getJSONObject(it).getString("id") == id }
        if (index == null) profiles.put(item) else profiles.put(index, item)
        if (json.isNull("activeId")) json.put("activeId", id)
        write(catalogFile, json)
    }
    @Synchronized fun activate(id: String) {
        val json = rawCatalog(); val profiles = json.getJSONArray("profiles")
        require((0 until profiles.length()).any { profiles.getJSONObject(it).getString("id") == id })
        json.put("activeId", id); write(catalogFile, json)
    }
    @Synchronized fun delete(id: String) {
        val json = rawCatalog(); val profiles = json.getJSONArray("profiles")
        val index = (0 until profiles.length()).single { profiles.getJSONObject(it).getString("id") == id }
        profiles.remove(index)
        if (json.optString("activeId") == id) json.put("activeId", if (profiles.length() == 0) JSONObject.NULL else profiles.getJSONObject(0).getString("id"))
        write(catalogFile, json)
    }
    /** Compatibility entry point used by existing clients; no destructive migration. */
    @Synchronized fun save(endpoint: String, model: String, replacement: String?) {
        if (exists(catalogFile)) {
            val active = catalog().active
            saveProfile(AiModelDraft(active?.id, active?.name ?: model.trim(), endpoint, model, replacement, active?.parameters ?: AiParameters()))
            return
        }
        val normalized = endpoint(endpoint)
        require(model.isNotBlank() && model.length <= 200 && !model.any(Char::isISOControl))
        val old = if (replacement == null) load() else AiConfig()
        if (replacement == null && old.key.isNotEmpty() && origin(old.endpoint) != origin(normalized)) throw AiOriginChanged()
        write(file, encrypt(JSONObject().put("endpoint", normalized).put("model", model.trim()), replacement ?: old.key))
    }
}
