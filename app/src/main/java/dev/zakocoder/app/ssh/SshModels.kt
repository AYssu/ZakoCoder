package dev.zakocoder.app.ssh

import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

enum class SshAuth { Password, PrivateKey }

data class SshProfile(
    val id: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val auth: SshAuth = SshAuth.Password,
    val workingDirectory: String = "/",
    val tmuxEnabled: Boolean = false,
    val hostFingerprint: String? = null,
    val hasCredential: Boolean = false,
)

data class SshHostKey(val profileId: String, val host: String, val port: Int, val algorithm: String, val fingerprint: String)
data class SshState(
    val profiles: List<SshProfile> = emptyList(),
    val busy: Boolean = false,
    val activeProfileId: String? = null,
    val pendingHostKey: SshHostKey? = null,
    val error: String? = null,
)

/** Codes only: raw SSH errors can contain remote-controlled strings and authentication material. */
class SshException(val code: String, cause: Throwable? = null) : IllegalStateException(code, cause)

internal class SshCredentials(val password: String = "", val privateKey: String = "", val passphrase: String = "") {
    override fun toString() = "SshCredentials(<redacted>)"
}

internal object SshSafety {
    const val MAX_BYTES = 128 * 1024
    const val MAX_FILE_BYTES = 1024 * 1024
    fun validate(profile: SshProfile) {
        check(profile.id.matches(Regex("[A-Za-z0-9_-]{1,100}")), "invalid_profile")
        check(profile.host.isNotBlank() && profile.host.length <= 253 &&
            profile.host.none { it.isWhitespace() || it.isISOControl() || it in "/@?#[]" }, "invalid_host")
        check(profile.port in 1..65535, "invalid_port")
        check(profile.username.isNotBlank() && profile.username.length <= 128 &&
            profile.username.none { it.isISOControl() }, "invalid_username")
        check(profile.workingDirectory.startsWith('/') && profile.workingDirectory.length <= 4096 &&
            profile.workingDirectory.none { it.isISOControl() } &&
            profile.workingDirectory.split('/').none { it == ".." }, "invalid_directory")
    }
    fun check(allowed: Boolean, code: String) { if (!allowed) throw SshException(code) }
    fun quote(value: String): String {
        check(!value.contains('\u0000'), "invalid_argument")
        return "'" + value.replace("'", "'\\''") + "'"
    }
    fun relativePath(value: String, allowRoot: Boolean = false): String {
        check(value.length <= 4096 && value.none { it.isISOControl() } && !value.startsWith('/'), "invalid_path")
        val parts = value.split('/').filter { it.isNotEmpty() && it != "." }
        check(parts.none { it == ".." }, "invalid_path")
        check(allowRoot || parts.isNotEmpty(), "invalid_path")
        return parts.joinToString("/")
    }
    /** Byte offsets are stable across pages; never return replacement characters for split codepoints. */
    fun utf8Page(bytes: ByteArray, offset: Int, limit: Int): ByteArray {
        check(offset >= 0 && limit > 0, "invalid_range")
        if (offset >= bytes.size) return byteArrayOf()
        fun continuation(index: Int) = bytes[index].toInt() and 0xc0 == 0x80
        check(!continuation(offset), "invalid_range")
        var end = minOf(bytes.size.toLong(), offset.toLong() + limit).toInt()
        while (end > offset && end < bytes.size && continuation(end)) end--
        check(end > offset, "invalid_range")
        val page = bytes.copyOfRange(offset, end)
        try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(page))
        } catch (error: java.nio.charset.CharacterCodingException) { throw SshException("not_utf8", error) }
        return page
    }
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun fingerprint(key: ByteArray) = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
    fun replaceExactlyOnce(content: String, old: String, new: String): String {
        check(old.isNotEmpty(), "empty_match")
        val index = content.indexOf(old)
        check(index >= 0 && content.indexOf(old, index + 1) < 0, "ambiguous_match")
        return content.substring(0, index) + new + content.substring(index + old.length)
    }
    fun taskName(profileId: String, taskId: String): String {
        check(taskId.matches(Regex("[0-9a-f]{32}")), "invalid_task")
        return "zako_${sha(profileId.toByteArray()).take(12)}_$taskId"
    }
    fun literalSftpPath(value: String): String = value.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?")
}
