package dev.zakocoder.app.device

data class VirtualScreenState(
    val active: Boolean = false,
    val busy: Boolean = false,
    val displayId: Int? = null,
    val width: Int = 720,
    val height: Int = 1600,
    val densityDpi: Int = 320,
    val packageName: String? = null,
    val error: String? = null,
)

/** Root display input must never be silently routed to the physical display. */
internal fun requireVirtualDisplayId(displayId: Int): Int {
    check(displayId > 0) { "virtual_display_identity" }
    return displayId
}

internal fun validateVirtualScreenSize(width: Int, height: Int, densityDpi: Int) {
    require(width in 240..1920 && height in 240..2560 && width.toLong() * height <= 3_686_400) { "virtual_display_size" }
    require(densityDpi in 120..640) { "virtual_display_density" }
}

/** Unknown window flags are unsafe to capture. Only the selected nonzero display is inspected. */
internal fun virtualDisplayCaptureAllowed(dump: String, displayId: Int): Boolean {
    if (displayId <= 0 || dump.length > 2_097_152) return false
    val headers = Regex("(?m)^\\s*Window #\\d+ Window\\{").findAll(dump).toList()
    val blocks = headers.mapIndexed { index, header -> dump.substring(header.range.first, headers.getOrNull(index + 1)?.range?.first ?: dump.length) }
        .filter { Regex("\\bmDisplayId=$displayId\\b").containsMatchIn(it) }
    if (blocks.isEmpty()) return false
    return blocks.all { block ->
        if (Regex("\\bSECURE\\b|FLAG_SECURE").containsMatchIn(block)) return@all false
        val flags = Regex("\\bfl=([^\\r\\n}]*)").findAll(block).map { it.groupValues[1].trim() }.toList()
        flags.isNotEmpty() && flags.all { value ->
            val tokens = value.split(Regex("\\s+"))
            tokens.isNotEmpty() && tokens.all { token ->
                if (token.matches(Regex("(?:0x)?[0-9a-fA-F]+"))) {
                    token.removePrefix("0x").toLongOrNull(16)?.let { (it and 0x2000L) == 0L } == true
                } else token.matches(Regex("[A-Z][A-Z0-9_]*")) && token != "SECURE"
            }
        }
    }
}
