package dev.zakocoder.app.ai

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AiImagesTest {
    private fun image() = AiImageAttachment(UUID.randomUUID().toString(), "screen.png", "image/jpeg", 1600, 900, AiImages.MAX_IMAGE_BYTES)
    @Test fun metadataIsBoundedAndDoesNotContainLocationOrPayload() {
        val image = image()
        AiImages.validate(image)
        assertFalse(image.toString().contains("base64"))
        assertThrows(IllegalArgumentException::class.java) { image.copy(id = "../other") }
        assertThrows(IllegalArgumentException::class.java) { image.copy(id = "123E4567-E89B-12D3-A456-426614174000") }
        assertThrows(IllegalArgumentException::class.java) { image.copy(mimeType = "image/svg+xml") }
        assertThrows(IllegalArgumentException::class.java) { image.copy(width = 1601) }
        assertThrows(IllegalArgumentException::class.java) { image.copy(height = 0) }
        assertThrows(IllegalArgumentException::class.java) { image.copy(bytes = AiImages.MAX_IMAGE_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { image.copy(displayName = "bad\nname") }
    }
    @Test fun batchBudgetAllowsOldTextOnlyEntriesAndFourNormalizedImages() {
        AiImages.validateBatch(emptyList())
        AiImages.validateBatch(List(4) { image() })
        assertThrows(IllegalArgumentException::class.java) { AiImages.validateBatch(List(5) { image() }) }
        val duplicate = image()
        assertThrows(IllegalArgumentException::class.java) { AiImages.validateBatch(listOf(duplicate, duplicate)) }
        assertFalse(AiImages.validId("123E4567-E89B-12D3-A456-426614174000"))
    }
}
