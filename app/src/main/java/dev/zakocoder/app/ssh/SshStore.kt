package dev.zakocoder.app.ssh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** Entire catalog is authenticated/encrypted and excluded from Android backup. Blocking IO only. */
internal class SshStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "ssh-connections-v1.json"))
    private val alias = "zakocoder-ssh-v1"
    data class Entry(val profile: SshProfile, val credential: SshCredentials, val tasks: Set<String> = emptySet()) {
        override fun toString() = "SshEntry(<redacted>)"
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): List<Entry> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val bytes = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                SshSafety.check(output.size() + count <= 4 * 1024 * 1024, "storage_invalid")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        SshSafety.check(bytes.size <= 4 * 1024 * 1024, "storage_invalid")
        val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(envelope.getString("iv"))))
        cipher.updateAAD("ssh-connections-v1".toByteArray())
        val rows = JSONArray(cipher.doFinal(Base64.getDecoder().decode(envelope.getString("data"))).toString(Charsets.UTF_8))
        SshSafety.check(rows.length() <= 32, "storage_invalid")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val credential = SshCredentials(row.getString("password"), row.getString("privateKey"), row.getString("passphrase"))
            val profile = SshProfile(row.getString("id"), row.getString("host"), row.getInt("port"), row.getString("username"),
                SshAuth.valueOf(row.getString("auth")), row.getString("directory"), row.getBoolean("tmux"),
                row.optString("fingerprint").takeIf { it.isNotEmpty() },
                if (row.getString("auth") == SshAuth.Password.name) credential.password.isNotEmpty() else credential.privateKey.isNotEmpty())
            SshSafety.validate(profile)
            val taskRows = row.optJSONArray("tasks") ?: JSONArray()
            SshSafety.check(taskRows.length() <= 64, "storage_invalid")
            val tasks = (0 until taskRows.length()).map { taskRows.getString(it) }.toSet()
            tasks.forEach { SshSafety.taskName(profile.id, it) }
            Entry(profile, credential, tasks)
        }.also { SshSafety.check(it.map { row -> row.profile.id }.distinct().size == it.size, "storage_invalid") }
    }
    fun write(entries: List<Entry>) {
        SshSafety.check(entries.size <= 32, "profile_limit")
        val rows = JSONArray()
        entries.forEach { (p, c, tasks) ->
            rows.put(JSONObject().put("id", p.id).put("host", p.host).put("port", p.port).put("username", p.username)
                .put("auth", p.auth.name).put("directory", p.workingDirectory).put("tmux", p.tmuxEnabled)
                .put("fingerprint", p.hostFingerprint.orEmpty()).put("password", c.password)
                .put("privateKey", c.privateKey).put("passphrase", c.passphrase).put("tasks", JSONArray(tasks.toList())))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD("ssh-connections-v1".toByteArray())
        val encrypted = cipher.doFinal(rows.toString().toByteArray())
        val bytes = JSONObject().put("iv", Base64.getEncoder().encodeToString(cipher.iv))
            .put("data", Base64.getEncoder().encodeToString(encrypted)).toString().toByteArray()
        SshSafety.check(bytes.size <= 4 * 1024 * 1024, "storage_limit")
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (error: Throwable) { file.failWrite(output); throw error }
    }
}
