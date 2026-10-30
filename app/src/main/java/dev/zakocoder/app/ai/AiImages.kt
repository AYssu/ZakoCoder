package dev.zakocoder.app.ai

import java.util.UUID
import dev.zakocoder.app.R

class AiImageException(val reason: Reason) : Exception(reason.name) {
    enum class Reason(val label: Int) {
        Invalid(R.string.ai_image_invalid), TooLarge(R.string.ai_image_too_large),
        Capacity(R.string.ai_image_capacity), Missing(R.string.ai_image_missing), Storage(R.string.ai_image_storage)
    }
}

/** Private-store reference only. Never contains source URIs, filesystem paths or encoded image data. */
data class AiImageAttachment(val id: String, val displayName: String, val mimeType: String,
    val width: Int, val height: Int, val bytes: Int) {
    init { AiImages.validate(this) }
}

object AiImages {
    const val MAX_SOURCE_BYTES = 12 * 1024 * 1024
    const val MAX_EDGE = 1600
    const val MAX_SOURCE_PIXELS = 100_000_000L
    const val MAX_IMAGE_BYTES = 1024 * 1024
    const val MAX_PER_TURN = 4
    const val MAX_TURN_BYTES = 4 * MAX_IMAGE_BYTES
    const val MAX_STORED_IMAGES = 256
    const val MAX_STORED_BYTES = 128L * 1024 * 1024

    fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    fun validate(image: AiImageAttachment) {
        require(validId(image.id))
        require(image.displayName.length in 1..120 && image.displayName.none(Char::isISOControl))
        require(image.mimeType == "image/jpeg")
        require(image.width in 1..MAX_EDGE && image.height in 1..MAX_EDGE)
        require(image.bytes in 1..MAX_IMAGE_BYTES)
    }
    fun validateBatch(images: Collection<AiImageAttachment>) {
        require(images.size <= MAX_PER_TURN && images.map { it.id }.distinct().size == images.size)
        images.forEach(::validate)
        require(images.sumOf { it.bytes.toLong() } <= MAX_TURN_BYTES)
    }
}
