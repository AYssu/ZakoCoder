package dev.zakocoder.app.mcp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Entire configuration is encrypted, including URLs, arguments, headers and environment. IO only. */
internal class McpStore(context: Context, private val alias: String = "zakocoder-mcp-v1") {
    private val file = AtomicFile(File(context.noBackupFilesDir, "mcp-servers-v1.json"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): List<McpServer> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val bytes = file.openRead().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val size = input.read(buffer); if (size < 0) break; require(out.size() + size <= 2 * 1024 * 1024); out.write(buffer, 0, size) }
            out.toByteArray()
        }
        val envelope = JSONObject(bytes.toString(Charsets.UTF_8)); require(envelope.getInt("schemaVersion") == 1)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)))
        cipher.updateAAD("zakocoder-mcp-v1".toByteArray())
        val clear = cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP))
        val entries = JSONArray(clear.toString(Charsets.UTF_8)); require(entries.length() <= 32)
        val result = (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            val parsed = McpConfigParser.parse(JSONObject().put("mcpServers", JSONObject().put(item.getString("name"), item.getJSONObject("config"))).toString()).single()
            parsed.copy(id = item.getString("id"), enabled = item.getBoolean("enabled")).also(McpConfigParser::validate)
        }
        require(result.map { it.id }.distinct().size == result.size && result.map { it.name }.distinct().size == result.size)
        return result
    }
    fun save(servers: List<McpServer>) {
        require(servers.size <= 32)
        val array = JSONArray()
        servers.forEach { server ->
            McpConfigParser.validate(server)
            val config = JSONObject().put("type", when (server.transport) { McpTransport.Stdio -> "stdio"; McpTransport.StreamableHttp -> "streamable_http"; McpTransport.Sse -> "sse" })
            if (server.transport == McpTransport.Stdio) config.put("command", server.command).put("args", JSONArray(server.args)).put("env", JSONObject(server.env))
            else config.put("url", server.url).put("headers", JSONObject(server.headers))
            array.put(JSONObject().put("id", server.id).put("name", server.name).put("enabled", server.enabled).put("config", config))
        }
        val clear = array.toString().toByteArray(); require(clear.size <= 512 * 1024)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD("zakocoder-mcp-v1".toByteArray()) }
        val envelope = JSONObject().put("schemaVersion", 1).put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(cipher.doFinal(clear), Base64.NO_WRAP)).toString().toByteArray()
        val stream = file.startWrite()
        try { stream.write(envelope); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }
}
