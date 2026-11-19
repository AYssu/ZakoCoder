package dev.zakocoder.app.screentest

import android.net.Uri

enum class ScreenOverlayPhase { Ready, Verifying, Acting, Succeeded, Failed, Cancelled }

data class ScreenOverlayProgress(
    val phase: ScreenOverlayPhase = ScreenOverlayPhase.Ready,
    val action: String = "",
    val title: String = "",
    val detail: String = "",
)

data class ScreenTestState(
    val projectId: String? = null,
    val packageName: String? = null,
    val taskId: String? = null,
    val leaseId: String? = null,
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val active: Boolean = false,
    val shareScreenshots: Boolean = false,
    val rootGranted: Boolean = false,
    val busy: Boolean = false,
    val summary: String = "",
    val reportPath: String? = null,
    val overlayProgress: ScreenOverlayProgress = ScreenOverlayProgress(),
)

data class ScreenTestResult(val output: String, val imageUri: Uri? = null, val packageName: String? = null)

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

/** Includes bounded Root display and node operations; never retries input. */
internal fun screenActionTimeoutMillis(action: String): Long = when (action) {
    "launch" -> 60_000L
    "observe", "assert_text", "wait_until", "click", "long_click", "input_text", "swipe", "scroll", "back", "screenshot" -> 60_000L
    else -> 20_000L
}
