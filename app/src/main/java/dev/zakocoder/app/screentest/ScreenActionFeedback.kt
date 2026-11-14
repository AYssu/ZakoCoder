package dev.zakocoder.app.screentest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.MessageDigest

/** Transient fingerprints only: no screenshot, node text or typed input is retained here. */
internal data class ScreenObservationFingerprint(val token: String, val digest: String, val truncated: Boolean)

internal data class ScreenActionReceipt(
    val dispatched: Boolean,
    val observedChange: Boolean?,
    val verified: Boolean,
    val unknownReason: String?,
) {
    companion object {
        fun action(before: ScreenObservationFingerprint?, after: ScreenObservationFingerprint?): ScreenActionReceipt {
            val changed = if (before == null || after == null) null
                else if (before.digest != after.digest) true
                else if (before.truncated || after.truncated) null else false
            val reason = when {
                after == null -> "observation_unavailable"
                before == null -> "missing_baseline"
                changed == null -> "partial_observation"
                changed -> "postcondition_not_specified"
                else -> "unchanged_screen"
            }
            // A changed screen is evidence of a change, never evidence of the user's intent.
            return ScreenActionReceipt(true, changed, false, reason)
        }
    }
}

/** Wraps already-authorized backend operations. Side effects are dispatched exactly once. */
internal class ScreenActionFeedback {
    @Volatile private var baseline: ScreenObservationFingerprint? = null
    @Volatile private var lease: String? = null
    fun clear() { baseline = null; lease = null }

    suspend fun execute(scope: String, args: JSONObject, perform: suspend (JSONObject) -> ScreenTestResult): ScreenTestResult {
        if (lease != scope) { baseline = null; lease = scope }
        val action = args.getString("action")
        if (action == "wait_until") return waitUntil(args, perform)
        if (action == "observe") return perform(args).also { remember(it) }
        if (action == "assert_text") {
            val result = perform(args)
            return receipt(result, ScreenActionReceipt(false, null, true, null))
        }
        if (action !in SIDE_EFFECTS) return perform(args)
        val before = if (action == "back") {
            // BACK has no node token. Capture a current baseline before it, when possible.
            observeSafely(perform)?.let(::fingerprint)
        } else baseline?.takeIf { it.token == args.optString("snapshot") }
        val result = perform(args) // Never retry, even when the after-observation is unavailable.
        baseline = null
        var observation: ScreenTestResult? = null
        withTimeoutOrNull(8000) {
            delay(150)
            observation = observeSafely(perform)
            val after = observation?.let(::fingerprint)
            if (after != null && before != null && after.digest == before.digest && !after.truncated) {
                delay(350)
                observeSafely(perform)?.let { observation = it }
            }
        }
        val after = observation?.let(::fingerprint)
        baseline = after
        return receipt(result, ScreenActionReceipt.action(before, after), observation)
    }

    private suspend fun waitUntil(args: JSONObject, perform: suspend (JSONObject) -> ScreenTestResult): ScreenTestResult {
        val text = args.get("text")
        require(text is String)
        require(text.isNotBlank() && text.length <= 1000)
        val rawTimeout = if (args.has("timeout_ms")) args.get("timeout_ms") else 5000
        require(rawTimeout is Int || rawTimeout is Long)
        val timeout = (rawTimeout as Number).toLong()
        require(timeout in 1..20000)
        var last: ScreenTestResult? = null
        var matched = false
        var unavailable = false
        withTimeoutOrNull(timeout) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val observation = observeSafely(perform)
                if (observation == null) { unavailable = true; break }
                last = observation
                remember(observation)
                if (visibleTextMatches(JSONObject(observation.output), text)) { matched = true; break }
                delay(250)
            }
        }
        val output = JSONObject().put("condition", "text_visible")
            .put("timed_out", !matched && !unavailable).put("next", if (matched) "continue" else "observe")
        return receipt(ScreenTestResult(output.toString(), packageName = last?.packageName),
            ScreenActionReceipt(false, null, matched, if (matched) null else if (unavailable) "observation_unavailable" else "condition_timeout"), last)
    }

    private suspend fun observeSafely(perform: suspend (JSONObject) -> ScreenTestResult): ScreenTestResult? = try {
        perform(JSONObject().put("action", "observe")).also { fingerprint(it) }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private fun remember(result: ScreenTestResult) { baseline = fingerprint(result) }
    private fun fingerprint(result: ScreenTestResult): ScreenObservationFingerprint {
        val observation = JSONObject(result.output)
        val nodes = observation.getJSONArray("nodes")
        val text = observation.optString("package") + "\u001f" + nodes.toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        return ScreenObservationFingerprint(observation.getString("snapshot"), digest, observation.optBoolean("truncated"))
    }

    private fun receipt(result: ScreenTestResult, receipt: ScreenActionReceipt, observation: ScreenTestResult? = null): ScreenTestResult {
        val output = JSONObject(result.output).put("dispatched", receipt.dispatched)
            .put("observed_change", receipt.observedChange ?: JSONObject.NULL).put("verified", receipt.verified)
            .put("unknown_reason", receipt.unknownReason ?: JSONObject.NULL)
        observation?.let { output.put("observation", JSONObject(it.output)) }
        if (!receipt.verified && receipt.dispatched) output.put("next", "wait_until_or_assert_text")
        return result.copy(output = output.toString(), packageName = observation?.packageName ?: result.packageName)
    }

    companion object {
        private val SIDE_EFFECTS = setOf("click", "long_click", "input_text", "swipe", "scroll", "back")
        internal fun visibleTextMatches(observation: JSONObject, expected: String): Boolean {
            val nodes = observation.getJSONArray("nodes")
            return (0 until nodes.length()).any { index -> nodes.getJSONObject(index).let {
                !it.optBoolean("sensitive") && (it.optString("text").contains(expected) || it.optString("description").contains(expected))
            } }
        }
    }
}
