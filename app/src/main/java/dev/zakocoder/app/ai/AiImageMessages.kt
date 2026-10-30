package dev.zakocoder.app.ai

import org.json.JSONArray
import org.json.JSONObject

/** Persistent messages contain metadata only; image bytes enter the wire request on IO. */
internal fun encodeAiImage(image: AiImageAttachment): JSONObject = JSONObject()
    .put("id", image.id).put("displayName", image.displayName).put("mimeType", image.mimeType)
    .put("width", image.width).put("height", image.height).put("bytes", image.bytes)

internal fun decodeAiImages(array: JSONArray): List<AiImageAttachment> {
    require(array.length() <= AiImages.MAX_PER_TURN)
    return (0 until array.length()).map { index -> array.getJSONObject(index).let {
        AiImageAttachment(it.getString("id"), it.getString("displayName"), it.getString("mimeType"),
            it.getInt("width"), it.getInt("height"), it.getInt("bytes"))
    } }.also(AiImages::validateBatch)
}

internal fun imageMessage(text: String, images: List<AiImageAttachment>): JSONObject =
    JSONObject().put("role", "user").put("content", text).apply {
        if (images.isNotEmpty()) put("zako_images", JSONArray(images.map(::encodeAiImage)))
    }

internal data class AiWireImages(val messages: JSONArray, val diagnosticMessages: JSONArray, val omitted: Int)

/** Send at most the latest four images, including current-turn inputs; mark older omissions. */
internal suspend fun wireImageMessages(source: JSONArray, resolve: (suspend (AiImageAttachment) -> String)?): AiWireImages {
    val references = mutableListOf<AiImageAttachment>()
    for (i in 0 until source.length()) source.getJSONObject(i).optJSONArray("zako_images")?.let {
        require(source.getJSONObject(i).getString("role") == "user")
        references += decodeAiImages(it)
    }
    val selected = references.asReversed().distinctBy { it.id }.take(AiImages.MAX_PER_TURN).map { it.id }.toSet()
    val latestPosition = mutableMapOf<String, Int>()
    for (i in 0 until source.length()) source.getJSONObject(i).optJSONArray("zako_images")?.let { array ->
        decodeAiImages(array).forEach { latestPosition[it.id] = i }
    }
    val data = mutableMapOf<String, String>()
    val wire = JSONArray()
    val diagnostic = JSONArray()
    var omitted = 0
    for (i in 0 until source.length()) {
        val message = JSONObject(source.getJSONObject(i).toString())
        val images = message.optJSONArray("zako_images")?.let(::decodeAiImages).orEmpty()
        message.remove("zako_images")
        val text = message.optString("content")
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        val logged = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        for (image in images) {
            if (image.id !in selected || latestPosition[image.id] != i) {
                omitted++
                val note = JSONObject().put("type", "text").put("text",
                    if (image.id in selected) "[Repeated image ${image.displayName}: pixels are supplied with its latest occurrence later in this request.]"
                    else "[Prior image omitted from this request: ${image.displayName}. Attach it again if its pixels are needed.]")
                content.put(note); logged.put(JSONObject(note.toString()))
            } else {
                requireNotNull(resolve) { "Image resolver required" }
                val url = data[image.id] ?: resolve(image).also { data[image.id] = it }
                require(url.startsWith("data:image/jpeg;base64,") && url.length <= AiImages.MAX_IMAGE_BYTES * 4 / 3 + 128)
                content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
                logged.put(JSONObject().put("type", "text").put("text", "[image omitted] ${image.displayName} (${image.width}×${image.height}, ${image.bytes} bytes)"))
            }
        }
        wire.put(if (images.isEmpty()) message else JSONObject(message.toString()).put("content", content))
        diagnostic.put(if (images.isEmpty()) message else JSONObject(message.toString()).put("content", logged))
    }
    return AiWireImages(wire, diagnostic, omitted)
}
