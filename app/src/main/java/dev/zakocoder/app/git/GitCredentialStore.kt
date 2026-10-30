package dev.zakocoder.app.git

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Exact repository binding: a token is never reused for another project, host or repository path. */
internal fun gitCredentialBinding(projectId: String, remoteUrl: String): String {
    require(UUID.fromString(projectId).toString() == projectId)
    val uri = URI(remoteUrl)
    require(remoteUrl.length <= 2000 && remoteUrl.none(Char::isISOControl))
    require(uri.scheme == "https" && uri.userInfo == null && uri.query == null && uri.fragment == null)
    require(!uri.host.isNullOrBlank() && (uri.port == -1 || uri.port in 1..65535))
    require(!uri.rawPath.isNullOrBlank() && uri.rawPath.startsWith("/") && uri.normalize().rawPath == uri.rawPath)
    val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
    return "$projectId|https://${uri.host.lowercase()}$port${uri.rawPath}"
}

internal class GitCredential(val username: String, val token: String) {
    override fun toString() = "GitCredential(<redacted>)"
    fun validate() {
        require(username.length in 1..128 && username.all { it.isLetterOrDigit() && it.code < 128 || it in "._-" })
        require(token.length in 1..8192 && token.all { it.code in 33..126 })
    }
}
internal data class GitCredentialSummary(val username: String)

/** Blocking IO; callers dispatch on IO. Entire catalog is encrypted and excluded from backup. */
internal class GitCredentialStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "git-credentials-v1.json"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("zakocoder-git-v1", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("zakocoder-git-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized private fun read(): MutableMap<String, GitCredential> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return mutableMapOf()
        val bytes = file.openRead().use { it.readBytesLimited(2 * 1024 * 1024) }
        val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(envelope.getString("iv"))))
        cipher.updateAAD("git-credentials-v1".toByteArray())
        val rows = JSONArray(cipher.doFinal(Base64.getDecoder().decode(envelope.getString("data"))).toString(Charsets.UTF_8))
        require(rows.length() <= 128)
        val result = mutableMapOf<String, GitCredential>()
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val binding = row.getString("binding")
            val id = binding.substringBefore('|')
            require(gitCredentialBinding(id, binding.substringAfter('|')) == binding)
            require(binding !in result)
            result[binding] = GitCredential(row.getString("username"), row.getString("token")).also { it.validate() }
        }
        return result
    }
    @Synchronized fun summary(projectId: String, remoteUrl: String): GitCredentialSummary? =
        read()[gitCredentialBinding(projectId, remoteUrl)]?.let { GitCredentialSummary(it.username) }
    @Synchronized fun credential(projectId: String, remoteUrl: String): GitCredential? = read()[gitCredentialBinding(projectId, remoteUrl)]
    @Synchronized fun save(projectId: String, remoteUrl: String, username: String, token: String) {
        val credential = GitCredential(username, token).also { it.validate() }
        val rows = read(); rows[gitCredentialBinding(projectId, remoteUrl)] = credential
        write(rows)
    }
    @Synchronized fun remove(projectId: String, remoteUrl: String) {
        val rows = read(); rows.remove(gitCredentialBinding(projectId, remoteUrl)); write(rows)
    }
    @Synchronized fun removeProject(projectId: String) {
        require(UUID.fromString(projectId).toString() == projectId)
        val rows = read()
        if (rows.keys.removeAll { it.startsWith("$projectId|") }) write(rows)
    }
    private fun write(entries: Map<String, GitCredential>) {
        require(entries.size <= 128)
        val rows = JSONArray()
        entries.forEach { (binding, credential) -> rows.put(JSONObject().put("binding", binding).put("username", credential.username).put("token", credential.token)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD("git-credentials-v1".toByteArray())
        val envelope = JSONObject().put("iv", Base64.getEncoder().encodeToString(cipher.iv))
            .put("data", Base64.getEncoder().encodeToString(cipher.doFinal(rows.toString().toByteArray()))).toString().toByteArray()
        require(envelope.size <= 2 * 1024 * 1024)
        val output = file.startWrite()
        try { output.write(envelope); file.finishWrite(output) } catch (error: Throwable) { file.failWrite(output); throw error }
    }
}
private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) { val count = read(buffer); if (count < 0) break; require(output.size() + count <= limit); output.write(buffer, 0, count) }
    return output.toByteArray()
}
