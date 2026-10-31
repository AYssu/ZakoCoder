package dev.zakocoder.app.screentest

import android.net.Uri

data class ScreenTestApk(
    val relativePath: String,
    val packageName: String,
    val label: String,
    val versionName: String,
    val debuggable: Boolean,
)

data class ScreenTestApkProposal(val apk: ScreenTestApk, val sha256: String)

enum class ScreenTestAccess { Normal, Accessibility, Root }

data class ScreenTestState(
    val projectId: String? = null,
    val packageName: String? = null,
    val apkPath: String? = null,
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val active: Boolean = false,
    val access: ScreenTestAccess = ScreenTestAccess.Normal,
    val shareScreenshots: Boolean = false,
    val rootGranted: Boolean = false,
    val installAllowed: Boolean = false,
    val busy: Boolean = false,
    val summary: String = "",
    val reportPath: String? = null,
    val overlayEnabled: Boolean = false,
    val overlayGranted: Boolean = false,
    val overlayVisible: Boolean = false,
    val overlayFailure: String = "",
    val overlayConversation: String = "",
)

data class ScreenTestResult(val output: String, val imageUri: Uri? = null)

interface ScreenTestBackend {
    suspend fun execute(packageName: String, args: org.json.JSONObject): ScreenTestResult
    fun stop()
}

class ScreenTestException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** A complete JSON response; budgets include escaping and UTF-8, never a substring of JSON. */
internal data class ScreenTestObservationOutput(val output: String, val exposedCount: Int)

internal fun boundedScreenObservation(
    packageName: String?, token: String, nodes: List<org.json.JSONObject>, truncated: Boolean = false,
): ScreenTestObservationOutput {
    val exposed = org.json.JSONArray()
    val payload = org.json.JSONObject().put("snapshot", token).put("package", packageName)
        .put("nodes", exposed).put("truncated", truncated)
    var count = 0
    for (node in nodes.take(80)) {
        exposed.put(node)
        val candidate = payload.toString()
        if (candidate.length > 60_000 || candidate.toByteArray(Charsets.UTF_8).size > 60_000) {
            exposed.remove(exposed.length() - 1)
            break
        }
        count++
    }
    payload.put("truncated", truncated || count < nodes.size)
    return ScreenTestObservationOutput(payload.toString(), count)
}

/** Includes artifact hashing and multiple bounded root commands; never retries input or installation. */
internal fun screenActionTimeoutMillis(action: String): Long = when (action) {
    "install", "launch" -> 60_000L
    "observe", "assert_text", "click", "long_click", "input_text", "swipe", "back", "screenshot" -> 60_000L
    else -> 20_000L
}

internal fun boundedOverlayText(text: String): String {
    val end = text.length.coerceAtMost(1200)
    val safeEnd = if (end > 0 && end < text.length && Character.isHighSurrogate(text[end - 1])) end - 1 else end
    return text.take(safeEnd)
}
