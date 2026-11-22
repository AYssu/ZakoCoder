package dev.zakocoder.app.ai

import java.io.IOException
import java.util.UUID

/** Only private archive identity and safe metadata; never a provider URI or filesystem path. */
data class AiFileAttachment(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val bytes: Long,
    val sha256: String,
) {
    init { AiFileAttachments.validate(this) }
}

object AiFileAttachments {
    const val MAX_FILE_BYTES = 32L * 1024 * 1024
    const val MAX_STORED_BYTES = 512L * 1024 * 1024
    const val MAX_STORED_FILES = 256
    const val MAX_CONVERSATION_FILES = 64

    fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    fun validate(attachment: AiFileAttachment) {
        require(validId(attachment.id))
        require(attachment.displayName.length in 1..120)
        require(attachment.displayName.none { it.isISOControl() || it == '/' || it == '\\' || Character.getType(it) == Character.FORMAT.toInt() })
        require(attachment.displayName != "." && attachment.displayName != "..")
        require(!attachment.displayName.last().isHighSurrogate())
        require(attachment.mimeType.matches(Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")))
        require(attachment.mimeType.length <= 120)
        require(attachment.bytes in 0..MAX_FILE_BYTES)
        require(attachment.sha256.matches(Regex("[a-f0-9]{64}")))
    }
}

/** Redacted reason only: provider errors may contain private URIs and names. */
class AiFileAttachmentException(val reason: Reason) : IOException("file_attachment_${reason.name.lowercase()}") {
    enum class Reason { Unavailable, TooLarge, Capacity, Unsupported }
}
