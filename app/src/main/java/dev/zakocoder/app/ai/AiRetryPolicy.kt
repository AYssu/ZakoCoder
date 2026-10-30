package dev.zakocoder.app.ai

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A replacement model request only; never a task/tool replay or an SSE resume. */
data class AiRetryEvent(val attempt: Int, val maxAttempts: Int, val delayMillis: Long,
    val diagnostic: String, val status: Int?)

internal object AiRetryPolicy {
    const val MAX_ATTEMPTS = 3
    const val MAX_SERVER_DELAY_MS = 30_000L
    private val statuses = setOf(408, 429, 500, 502, 503, 504)
    private val transientDiagnostics = setOf("dns_resolution_failed", "connect_failed", "network_read_or_connect_timeout",
        "request_timeout", "stream_eof", "network_io_failed", "missing_done")

    fun delayMillis(failedAttempt: Int, error: AiTransportException, jitterMillis: Long): Long? {
        if (failedAttempt !in 1 until MAX_ATTEMPTS) return null
        val transient = if (error.kind == AiFailure.Http) error.status in statuses
            else error.kind in setOf(AiFailure.Network, AiFailure.IdleTimeout, AiFailure.Protocol) && error.diagnostic in transientDiagnostics
        if (!transient) return null
        val server = error.retryAfterMillis
        // A server asking for longer is not silently retried sooner. Invalid Retry-After fails closed.
        if (server != null && (server < 0 || server > MAX_SERVER_DELAY_MS)) return null
        return maxOf((1_000L shl (failedAttempt - 1)) + jitterMillis.coerceIn(0, 250), server ?: 0)
    }

    /** null = absent; -1 = invalid/too large. Never retain the original header in logs. */
    fun retryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        if (value == null) return null
        val raw = value.trim()
        raw.toLongOrNull()?.let { seconds ->
            return if (seconds < 0 || seconds > MAX_SERVER_DELAY_MS / 1000) -1 else seconds * 1000
        }
        return try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT"); isLenient = false
            }
            val position = java.text.ParsePosition(0)
            val parsed = format.parse(raw, position) ?: return -1
            if (position.index != raw.length) return -1
            val wait = (parsed.time - nowMillis).coerceAtLeast(0)
            if (wait > MAX_SERVER_DELAY_MS) -1 else wait
        } catch (_: IllegalArgumentException) { -1 }
    }
}
