package dev.zakocoder.app.ai

import dev.zakocoder.app.settings.NetworkProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// A single response must leave room for the user request and other entries in 512k history.
internal const val AI_RESPONSE_TEXT_LIMIT = 256000

data class AiCall(val id: String, val name: String, val arguments: String)
data class AiReply(val text: String, val calls: List<AiCall>)
class AiTransportException(val status: Int? = null, val kind: AiFailure = AiFailure.Protocol, val diagnostic: String = "invalid_response") : IOException("AI request failed: ${kind.name}")
class ChatTransport(private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()) {
    // Settings change rarely, so cache the last (raw -> client) mapping instead of cloning per request.
    // Guarded because requests run on the IO dispatcher and can overlap across model probes/tasks.
    private val proxyLock = Any()
    private var proxyRaw: String? = null
    private var proxyClient: OkHttpClient? = null

    /**
     * Returns the base client when no proxy is configured (never mutating it), or a derived client
     * carrying the HTTP/SOCKS5 proxy. An invalid stored value is rejected by [NetworkProxy.parse];
     * callers map it to a network failure instead of silently going direct.
     */
    private fun clientFor(raw: String): OkHttpClient {
        if (raw.isBlank()) return client
        synchronized(proxyLock) {
            if (proxyRaw == raw) return requireNotNull(proxyClient)
            val derived = client.newBuilder().proxy(NetworkProxy.javaProxy(raw)).build()
            proxyRaw = raw
            proxyClient = derived
            return derived
        }
    }

    suspend fun request(config: AiConfig, messages: JSONArray, tools: JSONArray, forceTool: String? = null, proxy: String = "",
        toolDelta: (List<AiCallDraft>) -> Unit = {}, observe: (AiRequestLog) -> Unit = {}, delta: (String) -> Unit): AiReply = withContext(Dispatchers.IO) {
        val redactor = AiLogRedactor(config.key)
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1000000
        var record = AiRequestLog(model = redactor.clean(config.model), origin = AiConfigStore.endpoint(config.endpoint).toHttpUrl().let { "${it.scheme}://${it.host}:${it.port}" },
            contextChars = messages.toString().length)
        val lock = Any()
        fun update(change: (AiRequestLog) -> AiRequestLog) = synchronized(lock) {
            record = change(record).copy(elapsed = elapsed()); observe(record)
        }
        fun classify(error: Exception): AiFailure = when (error) {
            is AiTransportException -> error.kind
            is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> AiFailure.IdleTimeout
            is IOException -> AiFailure.Network
            else -> AiFailure.Protocol
        }
        fun diagnostic(error: Exception): String = when (error) {
            is AiTransportException -> error.diagnostic
            is java.net.UnknownHostException -> "dns_resolution_failed"
            is javax.net.ssl.SSLException -> "tls_failed"
            is java.net.ConnectException -> "connect_failed"
            is java.net.SocketTimeoutException -> "network_read_or_connect_timeout"
            is java.io.InterruptedIOException -> "request_timeout"
            is java.io.EOFException -> "stream_eof"
            is org.json.JSONException -> "malformed_json"
            is IOException -> "network_io_failed"
            else -> "invalid_response_structure"
        }
        val payload = JSONObject().put("model", config.model).put("messages", messages).put("stream", true).apply { if (tools.length() > 0) put("tools", tools) }
            .put("stream_options", JSONObject().put("include_usage", true)).apply {
            config.parameters.validate()
            config.parameters.maxTokens?.let { put("max_tokens", it) }
            config.parameters.temperature?.let { put("temperature", it) }
            config.parameters.topP?.let { put("top_p", it) }
            forceTool?.let { put("tool_choice", JSONObject().put("type", "function").put("function", JSONObject().put("name", it))) }
        }.toString()
        update { it.copy(request = redactor.clean(payload).take(240000), requestBytes = payload.toByteArray().size, truncated = payload.length > 240000) }
        if (payload.length > 8 * 1024 * 1024) { update { it.copy(complete = true, failure = AiFailure.ContextLimit, diagnostic = "request_size_limit") }; throw AiTransportException(kind = AiFailure.ContextLimit, diagnostic = "request_size_limit") }
        // Resolve before enqueueing: transport errors are classified by the existing failure kinds.
        val http = try { clientFor(proxy) } catch (_: IllegalArgumentException) {
            update { it.copy(complete = true, failure = AiFailure.Network, diagnostic = "invalid_proxy") }
            throw AiTransportException(kind = AiFailure.Network, diagnostic = "invalid_proxy")
        }
        val request = Request.Builder().url(AiConfigStore.endpoint(config.endpoint) + "/chat/completions")
            .apply { if (config.key.isNotEmpty()) header("Authorization", "Bearer ${config.key}") }
            .post(payload.toRequestBody("application/json".toMediaType())).build()
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
                update { if (it.complete) it else it.copy(complete = true, failure = AiFailure.Cancelled, diagnostic = "cancelled") }
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val kind = classify(e)
                    update { if (it.complete) it else it.copy(complete = true, failure = kind, diagnostic = diagnostic(e)) }
                    if (continuation.isActive) continuation.resumeWithException(AiTransportException(kind = kind))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val reply = response.use {
                            update { item -> item.copy(status = it.code) }
                            if (!it.isSuccessful) throw AiTransportException(it.code, AiFailure.Http, "http_status_${it.code}")
                            val source = it.body?.source() ?: throw AiTransportException(diagnostic = "missing_body")
                            val text = StringBuilder(); val calls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>()
                            var bytes = 0L; var finish = ""; var done = false
                            var events = 0; var lastPublished = -100L
                            var firstEvent: Long? = null; var firstText: Long? = null
                            var inputTokens: Long? = null; var outputTokens: Long? = null; var cachedTokens: Long? = null
                            fun publish(force: Boolean = false) {
                                val now = elapsed()
                                if (!force && now - lastPublished < 100) return
                                lastPublished = now
                                val argumentChars = calls.values.sumOf { value -> value.third.length }
                                // Only UI previews are copied repeatedly; execution retains complete arguments below.
                                toolDelta(calls.map { (index, value) -> AiCallDraft(index, value.second.toString(), value.third.substring(0, minOf(value.third.length, 64000))) })
                                val previewLimit = 16000 + config.key.length + 1024
                                val preview = buildString {
                                    append(text.substring(0, minOf(text.length, previewLimit)))
                                    for (value in calls.values) {
                                        if (length >= previewLimit) break
                                        append("\n${value.second}: ")
                                        append(value.third.substring(0, minOf(value.third.length, (previewLimit - length).coerceAtLeast(0))))
                                    }
                                }
                                update { item ->
                                    val point = AiStreamPoint(now, events, text.length + argumentChars, bytes)
                                    val points = if (item.points.lastOrNull()?.ms?.let { ms -> now / 1000 > ms / 1000 } != false) (item.points + point).takeLast(256)
                                        else item.points.dropLast(1) + point
                                    item.copy(firstEvent = firstEvent, firstText = firstText, events = events,
                                        textChars = text.length, argumentChars = argumentChars, bytes = bytes,
                                        inputTokens = inputTokens, outputTokens = outputTokens, cachedTokens = cachedTokens,
                                        finish = finish.takeIf { value -> value in setOf("stop", "length", "tool_calls", "content_filter", "function_call") } ?: "other",
                                        response = redactor.clean(preview).take(16000),
                                        truncated = item.truncated || text.length + argumentChars > 16000 || item.points.size >= 256, points = points)
                                }
                            }
                            try {
                                while (!source.exhausted()) {
                                    val line = try { source.readUtf8LineStrict(8L * 1024 * 1024) }
                                    catch (error: java.io.EOFException) {
                                        if (source.buffer.size > 8L * 1024 * 1024) throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "response_event_limit")
                                        throw error
                                    }
                                    // Decoded SSE data bytes; excludes comments/framing and compressed transport overhead.
                                    if (!line.startsWith("data:")) continue
                                    val data = line.removePrefix("data:").trim()
                                    bytes += data.toByteArray().size
                                    if (data == "[DONE]") { done = true; break }
                                    if (data.isEmpty()) continue
                                    val event = JSONObject(data)
                                    if (event.has("error")) throw AiTransportException(diagnostic = "provider_error_event")
                                    events++
                                    val now = elapsed()
                                    firstEvent = firstEvent ?: now
                                    val usage = event.optJSONObject("usage")
                                    fun number(name: String): Long? = usage?.takeUnless { u -> u.isNull(name) }?.optLong(name)?.takeIf { n -> n >= 0 }
                                    inputTokens = number("prompt_tokens") ?: inputTokens
                                    outputTokens = number("completion_tokens") ?: outputTokens
                                    cachedTokens = usage?.optJSONObject("prompt_tokens_details")?.let { details -> if (details.isNull("cached_tokens")) null else details.optLong("cached_tokens").takeIf { n -> n >= 0 } } ?: cachedTokens
                                    val choice = event.optJSONArray("choices")?.optJSONObject(0)
                                    if (choice != null && !choice.isNull("finish_reason")) finish = choice.optString("finish_reason")
                                    val part = choice?.optJSONObject("delta")
                                    val content = part?.optString("content", "")?.takeUnless { value -> value == "null" }.orEmpty()
                                    if (content.length > AI_RESPONSE_TEXT_LIMIT - text.length) throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "response_text_limit")
                                    text.append(content)
                                    if (call.isCanceled()) throw AiTransportException(kind = AiFailure.Cancelled)
                                    if (content.isNotEmpty()) { firstText = firstText ?: now; delta(content) }
                                    val toolParts = part?.optJSONArray("tool_calls")
                                    if (toolParts != null) for (index in 0 until toolParts.length()) {
                                        val tool = toolParts.getJSONObject(index); val position = tool.getInt("index")
                                        if (position !in 0..7) throw AiTransportException(kind = AiFailure.ToolLimit)
                                        val target = calls.getOrPut(position) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                                        target.first.append(tool.optString("id", ""))
                                        tool.optJSONObject("function")?.let { function -> target.second.append(function.optString("name", "")); target.third.append(function.optString("arguments", "")) }
                                        if (target.first.length > 200 || target.second.length > 100) throw AiTransportException(diagnostic = "invalid_tool_identity")
                                        if (calls.values.sumOf { it.third.length } > 8 * 1024 * 1024) throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "tool_arguments_limit")
                                    }
                                    publish()
                                }
                            } finally { publish(force = true) }
                            if (finish == "length") throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "finish_length")
                            if (!done) throw AiTransportException(diagnostic = "missing_done")
                            if ((calls.isEmpty() && finish != "stop") || (calls.isNotEmpty() && finish != "tool_calls")) throw AiTransportException(diagnostic = "unexpected_finish_reason")
                            if (calls.values.map { value -> value.first.toString() }.distinct().size != calls.size) throw AiTransportException(diagnostic = "duplicate_tool_id")
                            AiReply(text.toString(), calls.values.map { value ->
                                if (value.first.isEmpty() || value.second.isEmpty()) throw AiTransportException(diagnostic = "incomplete_tool_identity")
                                AiCall(value.first.toString(), value.second.toString(), value.third.toString())
                            })
                        }
                        update { if (it.complete) it else it.copy(complete = true) }
                        if (continuation.isActive) continuation.resume(reply)
                    } catch (error: Exception) {
                        val kind = classify(error)
                        update { if (it.complete) it else it.copy(complete = true, failure = kind, diagnostic = diagnostic(error)) }
                        if (continuation.isActive) continuation.resumeWithException(if (error is AiTransportException) error else AiTransportException(kind = kind))
                    }
                }
            })
        }
    }
    suspend fun models(config: AiConfig, proxy: String = ""): List<String> = withContext(Dispatchers.IO) {
        val http = try { clientFor(proxy) } catch (_: IllegalArgumentException) {
            throw AiTransportException(kind = AiFailure.Network, diagnostic = "invalid_proxy")
        }
        val request = Request.Builder().url(AiConfigStore.endpoint(config.endpoint) + "/models")
            .apply { if (config.key.isNotEmpty()) header("Authorization", "Bearer ${config.key}") }.build()
        suspendCancellableCoroutine { continuation ->
            val call = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build().newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(AiTransportException()) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw AiTransportException(it.code)
                            val source = it.body?.source() ?: throw AiTransportException()
                            source.request(1024 * 1024 + 1L)
                            if (source.buffer.size > 1024 * 1024) throw AiTransportException()
                            val data = JSONObject(source.readUtf8()).getJSONArray("data")
                            require(data.length() <= 5000)
                            (0 until data.length()).map { index -> data.getJSONObject(index).getString("id") }
                                .filter { id -> id.isNotBlank() && id.length <= 200 && !id.any(Char::isISOControl) }.distinct().sorted().take(2000)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(if (error is AiTransportException) error else AiTransportException()) }
                }
            })
        }
    }

}
