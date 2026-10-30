package dev.zakocoder.app.ai

import dev.zakocoder.app.settings.NetworkProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
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
/** Provider reasoning is opaque protocol context, never an assistant's displayed answer. */
data class AiReply(val text: String, val calls: List<AiCall>, val reasoningContent: String? = null, val responsesOutput: String? = null, val providerOutput: String? = null, val providerProtocol: String? = null) {
    fun asAssistantMessage(): JSONObject = JSONObject().put("role", "assistant").put("content", text).apply {
        reasoningContent?.let { put("reasoning_content", it) }
        if (calls.isNotEmpty()) put("tool_calls", JSONArray(calls.map { call ->
            JSONObject().put("id", call.id).put("type", "function")
                .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))
        }))
        responsesOutput?.let { put(RESPONSES_OUTPUT_KEY, JSONArray(it)) }
        providerOutput?.let { put(PROVIDER_OUTPUT_KEY, JSONObject().put("protocol", requireNotNull(providerProtocol)).put("output", JSONArray(it))) }
    }
}
class AiTransportException(val status: Int? = null, val kind: AiFailure = AiFailure.Protocol, val diagnostic: String = "invalid_response", val retryAfterMillis: Long? = null) : IOException("AI request failed: ${kind.name}") {
    companion object {
        /** Status-only classification: never retain or display an untrusted provider error body. */
        internal fun http(status: Int, retryAfterMillis: Long? = null) = AiTransportException(
            status, if (status == 402) AiFailure.Billing else AiFailure.Http, "http_status_$status", retryAfterMillis)
    }
}
class ChatTransport(private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build(),
    private val retryDelay: suspend (Long) -> Unit = { delay(it) }, private val maxAttempts: Int = AiRetryPolicy.MAX_ATTEMPTS) {
    init { require(maxAttempts in 1..AiRetryPolicy.MAX_ATTEMPTS) }
    private val safeClient = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
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
        if (raw.isBlank()) {
            // Disabling the proxy must clear the process-wide binding immediately; otherwise a
            // later SOCKS5 authentication elsewhere could still receive the old pair.
            NetworkProxy.configureSocksAuthentication(null)
            return safeClient
        }
        synchronized(proxyLock) {
            // OkHttp delegates SOCKS5 username/password authentication to the JDK global
            // authenticator. Bind it to this exact endpoint on every call (never a previous pair).
            NetworkProxy.configureSocksAuthentication(raw)
            if (proxyRaw == raw) return requireNotNull(proxyClient)
            val derived = safeClient.newBuilder().proxy(NetworkProxy.javaProxy(raw)).build()
            proxyRaw = raw
            proxyClient = derived
            return derived
        }
    }

    suspend fun request(config: AiConfig, messages: JSONArray, tools: JSONArray, forceTool: String? = null, proxy: String = "",
        imageData: (suspend (AiImageAttachment) -> String)? = null, toolDelta: (List<AiCallDraft>) -> Unit = {}, observe: (AiRequestLog) -> Unit = {}, reasoningDelta: (String) -> Unit = {},
        onRetry: (AiRetryEvent) -> Unit = {}, onRetryReset: () -> Unit = {}, delta: (String) -> Unit): AiReply = withContext(Dispatchers.IO) {
        val frozenMessages = messages.toString()
        val frozenTools = tools.toString()
        val frozenConfig = config.copy(requestHeaders = config.requestHeaders.toMap())
        val group = java.util.UUID.randomUUID().toString()
        var attempt = 1
        var precedingDelay = 0L
        var precedingReason = ""
        while (true) {
            currentCoroutineContext().ensureActive()
            var replacementStarted = false
            fun beginReplacement() {
                if (attempt > 1 && !replacementStarted) { onRetryReset(); replacementStarted = true }
            }
            try {
                val reply = requestAttempt(frozenConfig, JSONArray(frozenMessages), JSONArray(frozenTools), forceTool, proxy,
                    imageData, { drafts ->
                        if (drafts.any { it.name.isNotEmpty() || it.arguments.isNotEmpty() }) beginReplacement()
                        if (attempt == 1 || replacementStarted) toolDelta(drafts)
                    },
                    observe,
                    { fragment -> if (fragment.isNotEmpty()) beginReplacement(); reasoningDelta(fragment) },
                    group, attempt, precedingDelay, precedingReason,
                    { fragment -> if (fragment.isNotEmpty()) beginReplacement(); delta(fragment) })
                // A successful empty replacement must not leave an earlier failed answer visible.
                beginReplacement()
                return@withContext reply
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: AiTransportException) {
                currentCoroutineContext().ensureActive()
                val wait = if (attempt >= maxAttempts) null else AiRetryPolicy.delayMillis(attempt, error, kotlin.random.Random.nextLong(251))
                if (wait == null) throw error
                onRetry(AiRetryEvent(attempt + 1, maxAttempts, wait, error.diagnostic, error.status))
                retryDelay(wait)
                currentCoroutineContext().ensureActive()
                precedingDelay = wait; precedingReason = error.diagnostic; attempt++
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable retry state")
    }

    private suspend fun requestAttempt(config: AiConfig, messages: JSONArray, tools: JSONArray, forceTool: String?, proxy: String,
        imageData: (suspend (AiImageAttachment) -> String)?, toolDelta: (List<AiCallDraft>) -> Unit, observe: (AiRequestLog) -> Unit, reasoningDelta: (String) -> Unit,
        retryGroup: String, attempt: Int, retryDelayMs: Long, retryReason: String, delta: (String) -> Unit): AiReply = withContext(Dispatchers.IO) {
        AiRequestHeaders.validate(config.requestHeaders)
        val redactor = AiLogRedactor(config.key, config.requestHeaders.values)
        fun clean(value: String): String = redactor.clean(value)
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1000000
        var record = AiRequestLog(model = clean(config.model), origin = AiConfigStore.endpoint(config.endpoint).toHttpUrl().let { "${it.scheme}://${it.host}:${it.port}" },
            retryGroup = retryGroup, attempt = attempt, maxAttempts = maxAttempts, retryDelayMs = retryDelayMs, retryReason = retryReason, apiProtocol = config.protocol.name,
            contextChars = messages.toString().length, estimatedContextTokens = tokenBudget(config, messages, tools).inputTokens,
            contextWindowTokens = config.context.contextWindowTokens, contextOutputReserveTokens = config.parameters.maxTokens ?: minOf(8192, config.context.contextWindowTokens / 4))
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
        val imageMessages = wireImageMessages(messages, imageData)
        // Opaque protocol data belongs only to its originating API. Public text/tool pairs survive switches.
        listOf(imageMessages.messages, imageMessages.diagnosticMessages).forEach { array ->
            repeat(array.length()) {
                val message = array.getJSONObject(it)
                if (config.protocol != AiApiProtocol.Responses && message.remove(RESPONSES_OUTPUT_KEY) != null)
                    message.remove("reasoning_content")
                val metadata = message.optJSONObject(PROVIDER_OUTPUT_KEY)
                if (metadata != null && metadata.optString("protocol") != config.protocol.name) {
                    message.remove(PROVIDER_OUTPUT_KEY); message.remove("reasoning_content")
                }
            }
        }
        var address = ""
        val body = try {
            address = AiProviderRouting.requestUrl(config)
            when (config.protocol) {
            AiApiProtocol.Responses -> AiResponsesWire.request(config, imageMessages.messages, tools, forceTool)
            AiApiProtocol.Anthropic -> AiAnthropicWire.request(config, imageMessages.messages, tools, forceTool)
            AiApiProtocol.Gemini -> AiGeminiWire.request(config, imageMessages.messages, tools, forceTool)
            AiApiProtocol.ChatCompletions -> JSONObject().put("model", config.model).put("messages", imageMessages.messages).put("stream", true).apply { if (tools.length() > 0) put("tools", tools) }
            .put("stream_options", JSONObject().put("include_usage", true)).apply {
            config.parameters.validate()
            config.parameters.maxTokens?.let { put("max_tokens", it) }
            config.parameters.temperature?.let { put("temperature", it) }
            config.parameters.topP?.let { put("top_p", it) }
            config.parameters.frequencyPenalty?.let { put("frequency_penalty", it) }
            config.parameters.presencePenalty?.let { put("presence_penalty", it) }
            config.parameters.seed?.let { put("seed", it) }
            config.parameters.reasoningEffort?.let { put("reasoning_effort", it.wire) }
            if (config.parameters.thinking != AiThinking.Default) put("thinking", JSONObject().put("type", if (config.parameters.thinking == AiThinking.Enabled) "enabled" else "disabled"))
            forceTool?.let {
                put("tool_choice", JSONObject().put("type", "function").put("function", JSONObject().put("name", it)))
                // DeepSeek thinking rejects forced tools; this applies only to the explicit probe.
                // Exact official host detection cannot affect unrelated compatible providers.
                if (java.net.URI(config.endpoint).host.equals("api.deepseek.com", ignoreCase = true)) {
                    put("thinking", JSONObject().put("type", "disabled"))
                }
            }
        }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val mapped = if (error is AiTransportException) error else AiTransportException(kind = AiFailure.RequestConfiguration, diagnostic = "invalid_request_configuration")
            update { it.copy(complete = true, failure = mapped.kind, diagnostic = mapped.diagnostic) }
            throw mapped
        }
        val payload = body.toString()
        val diagnosticPayload = when (config.protocol) {
            AiApiProtocol.Responses -> AiResponsesWire.diagnostic(AiResponsesWire.request(config, imageMessages.diagnosticMessages, tools, forceTool))
            AiApiProtocol.Anthropic -> AiAnthropicWire.diagnostic(body)
            AiApiProtocol.Gemini -> AiGeminiWire.diagnostic(body)
            AiApiProtocol.ChatCompletions -> JSONObject(body.toString()).put("messages", imageMessages.diagnosticMessages)
        }.toString()
        update { it.copy(request = clean(diagnosticPayload).take(240000), requestBytes = payload.toByteArray().size,
            truncated = diagnosticPayload.length > 240000 || imageMessages.omitted > 0) }
        if (payload.toByteArray().size > AI_REQUEST_MAX_BYTES) { update { it.copy(complete = true, failure = AiFailure.LocalResourceLimit, diagnostic = "local_request_32mib_limit") }; throw AiTransportException(kind = AiFailure.LocalResourceLimit, diagnostic = "local_request_32mib_limit") }
        // Resolve before enqueueing: transport errors are classified by the existing failure kinds.
        val http = try { clientFor(proxy) } catch (_: IllegalArgumentException) {
            update { it.copy(complete = true, failure = AiFailure.Network, diagnostic = "invalid_proxy") }
            throw AiTransportException(kind = AiFailure.Network, diagnostic = "invalid_proxy")
        }
        val request = AiProviderRouting.headers(Request.Builder().url(address), config)
            .post(payload.toRequestBody("application/json".toMediaType())).build()
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation {
                // Publish ownership cancellation before socket callbacks can report its IOException.
                update { if (it.complete) it else it.copy(complete = true, failure = AiFailure.Cancelled, diagnostic = "cancelled") }
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val kind = classify(e)
                    update { if (it.complete) it else it.copy(complete = true, failure = kind, diagnostic = diagnostic(e)) }
                    if (continuation.isActive) continuation.resumeWithException(AiTransportException(kind = kind, diagnostic = diagnostic(e)))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val reply = response.use {
                            update { item -> item.copy(status = it.code) }
                            if (!it.isSuccessful) throw AiTransportException.http(it.code, AiRetryPolicy.retryAfterMillis(it.header("Retry-After")))
                            val source = it.body?.source() ?: throw AiTransportException(diagnostic = "missing_body")
                            if (config.protocol != AiApiProtocol.ChatCompletions) return@use readProvider(source, call, config, ::elapsed, ::clean, ::update,
                                delta, reasoningDelta, toolDelta)
                            val text = StringBuilder(); var reasoning: StringBuilder? = null; val calls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>()
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
                                val previewLimit = 16000 + ((config.requestHeaders.values + config.key).maxOfOrNull { it.length * 2 } ?: 0) + 1024
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
                                        response = clean(preview).take(16000),
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
                                    cachedTokens = usage?.optJSONObject("prompt_tokens_details")?.let { details -> if (details.isNull("cached_tokens")) null else details.optLong("cached_tokens").takeIf { n -> n >= 0 } } ?: number("prompt_cache_hit_tokens") ?: cachedTokens
                                    val choice = event.optJSONArray("choices")?.optJSONObject(0)
                                    if (choice != null && !choice.isNull("finish_reason")) finish = choice.optString("finish_reason")
                                    val part = choice?.optJSONObject("delta")
                                    // Presence of the provider field is the capability signal, including gateways.
                                    // Preserve every fragment verbatim for subsequent tools and user turns.
                                    // Do not mix it into visible Markdown or synthesize it for other models.
                                    if (part != null && !part.isNull("reasoning_content")) {
                                        val fragment = part.getString("reasoning_content")
                                        val target = reasoning ?: StringBuilder().also { reasoning = it }
                                        if (fragment.length > AI_RESPONSE_TEXT_LIMIT - target.length) throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "response_reasoning_limit")
                                        target.append(fragment)
                                        if (fragment.isNotEmpty()) reasoningDelta(fragment)
                                    }
                                    val content = part?.optString("content", "")?.takeUnless { value -> value == "null" }.orEmpty()
                                    if (content.length > AI_RESPONSE_TEXT_LIMIT - text.length) throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "response_text_limit")
                                    text.append(content)
                                    if (call.isCanceled()) throw AiTransportException(kind = AiFailure.Cancelled)
                                    if (content.isNotEmpty()) { firstText = firstText ?: now; delta(content) }
                                    val toolParts = part?.optJSONArray("tool_calls")
                                    val firstTool = calls.isEmpty() && toolParts != null && toolParts.length() > 0
                                    if (toolParts != null) for (index in 0 until toolParts.length()) {
                                        val tool = toolParts.getJSONObject(index); val position = tool.getInt("index")
                                        if (position !in 0..7) throw AiTransportException(kind = AiFailure.ToolLimit)
                                        val target = calls.getOrPut(position) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                                        target.first.append(tool.optString("id", ""))
                                        tool.optJSONObject("function")?.let { function -> target.second.append(function.optString("name", "")); target.third.append(function.optString("arguments", "")) }
                                        if (target.first.length > 200 || target.second.length > 100) throw AiTransportException(diagnostic = "invalid_tool_identity")
                                        if (calls.values.sumOf { it.third.length } > 8 * 1024 * 1024) throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "tool_arguments_limit")
                                    }
                                    publish(force = firstTool)
                                }
                            } finally { publish(force = true) }
                            if (finish == "length") throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "finish_length")
                            if (!done) throw AiTransportException(diagnostic = "missing_done")
                            if ((calls.isEmpty() && finish != "stop") || (calls.isNotEmpty() && finish != "tool_calls")) throw AiTransportException(diagnostic = "unexpected_finish_reason")
                            if (calls.values.map { value -> value.first.toString() }.distinct().size != calls.size) throw AiTransportException(diagnostic = "duplicate_tool_id")
                            AiReply(text.toString(), calls.values.map { value ->
                                if (value.first.isEmpty() || value.second.isEmpty()) throw AiTransportException(diagnostic = "incomplete_tool_identity")
                                AiCall(value.first.toString(), value.second.toString(), value.third.toString())
                            }, reasoning?.toString())
                        }
                        update { if (it.complete) it else it.copy(complete = true) }
                        if (continuation.isActive) continuation.resume(reply)
                    } catch (error: Exception) {
                        val kind = classify(error)
                        update { if (it.complete) it else it.copy(complete = true, failure = kind, diagnostic = diagnostic(error)) }
                        if (continuation.isActive) continuation.resumeWithException(if (error is AiTransportException) error else AiTransportException(kind = kind, diagnostic = diagnostic(error)))
                    }
                }
            })
        }
    }
    /** Typed provider events share request ownership, logging, cancellation and retry boundaries. */
    private fun readProvider(source: okio.BufferedSource, call: Call, config: AiConfig, elapsed: () -> Long,
        clean: (String) -> String, update: ((AiRequestLog) -> AiRequestLog) -> Unit,
        delta: (String) -> Unit, reasoningDelta: (String) -> Unit, toolDelta: (List<AiCallDraft>) -> Unit): AiReply {
        var firstEvent: Long? = null; var firstText: Long? = null
        var events = 0; var bytes = 0L; var lastPublished = -100L
        var drafts = emptyList<AiCallDraft>()
        val textDelta: (String) -> Unit = { fragment ->
            if (fragment.isNotEmpty()) { firstText = firstText ?: elapsed(); delta(fragment) }
        }
        val draftDelta: (List<AiCallDraft>) -> Unit = { drafts = it }
        val stream: AiProviderStream = when (config.protocol) {
            AiApiProtocol.Responses -> AiResponsesStream(textDelta, reasoningDelta, draftDelta)
            AiApiProtocol.Anthropic -> AiAnthropicStream(textDelta, reasoningDelta, draftDelta)
            AiApiProtocol.Gemini -> AiGeminiStream(textDelta, reasoningDelta, draftDelta)
            AiApiProtocol.ChatCompletions -> error("Chat has its own stream decoder")
        }
        fun publish(force: Boolean = false) {
            val now = elapsed()
            if (!force && now - lastPublished < 100) return
            lastPublished = now
            toolDelta(drafts)
            val preview = stream.text.take(16000 + ((config.requestHeaders.values + config.key).maxOfOrNull { it.length * 2 } ?: 0) + 1024)
            update { record ->
                val point = AiStreamPoint(now, events, stream.textChars + stream.argumentChars, bytes)
                val points = if (record.points.lastOrNull()?.ms?.let { ms -> now / 1000 > ms / 1000 } != false)
                    (record.points + point).takeLast(256) else record.points.dropLast(1) + point
                record.copy(firstEvent = firstEvent, firstText = firstText, events = events, textChars = stream.textChars,
                    argumentChars = stream.argumentChars, bytes = bytes, inputTokens = stream.inputTokens,
                    outputTokens = stream.outputTokens, cachedTokens = stream.cachedTokens, finish = stream.finish,
                    response = clean(preview).take(16000), truncated = record.truncated || stream.textChars + stream.argumentChars > 16000 || record.points.size >= 256,
                    points = points)
            }
        }
        val pending = StringBuilder(); var pendingBytes = 0
        fun dispatch() {
            if (pending.isEmpty()) return
            val data = pending.toString(); pending.setLength(0); pendingBytes = 0
            bytes += data.toByteArray().size
            if (data == "[DONE]") {
                if (!stream.done) throw AiTransportException(diagnostic = "missing_done")
                return
            }
            firstEvent = firstEvent ?: elapsed(); events++
            val hadDrafts = drafts.isNotEmpty()
            stream.accept(JSONObject(data))
            if (call.isCanceled()) throw AiTransportException(kind = AiFailure.Cancelled, diagnostic = "cancelled")
            publish(force = !hadDrafts && drafts.isNotEmpty())
        }
        try {
            while (!stream.done && !source.exhausted()) {
                val line = try { source.readUtf8LineStrict(8L * 1024 * 1024) }
                catch (error: java.io.EOFException) {
                    if (source.buffer.size > 8L * 1024 * 1024)
                        throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "response_event_limit")
                    throw error
                }
                if (line.isEmpty()) { dispatch(); continue }
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").removePrefix(" ")
                pendingBytes += data.toByteArray().size + 1
                if (pendingBytes > 8 * 1024 * 1024)
                    throw AiTransportException(kind = AiFailure.OutputLimit, diagnostic = "response_event_limit")
                if (pending.isNotEmpty()) pending.append('\n')
                pending.append(data)
            }
            if (!stream.done) dispatch()
            return stream.reply()
        } finally { publish(force = true) }
    }

    suspend fun models(config: AiConfig, proxy: String = ""): List<String> = withContext(Dispatchers.IO) {
        val http = try { clientFor(proxy) } catch (_: IllegalArgumentException) {
            throw AiTransportException(kind = AiFailure.Network, diagnostic = "invalid_proxy")
        }
        val found = linkedSetOf<String>()
        val seenPages = mutableSetOf<String>()
        var page: String? = null
        repeat(10) {
            currentCoroutineContext().ensureActive()
            val url = AiProviderRouting.modelsUrl(config).newBuilder().apply {
                page?.let { token -> addQueryParameter(if (config.protocol == AiApiProtocol.Gemini) "pageToken" else "after_id", token) }
            }.build()
            val request = AiProviderRouting.headers(Request.Builder().url(url), config).build()
            val json: JSONObject =         suspendCancellableCoroutine { continuation ->
            val call = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build().newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(AiTransportException()) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw AiTransportException.http(it.code)
                            val source = it.body?.source() ?: throw AiTransportException()
                            source.request(1024 * 1024 + 1L)
                            if (source.buffer.size > 1024 * 1024) throw AiTransportException()
                            JSONObject(source.readUtf8())
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(if (error is AiTransportException) error else AiTransportException()) }
                }
            })
            }
            found += AiProviderRouting.modelIds(json, config.protocol)
            if (found.size > 5000) throw AiTransportException(diagnostic = "model_catalog_limit")
            page = AiProviderRouting.nextPage(json, config.protocol) ?: return@withContext found.sorted().take(2000)
            if (!seenPages.add(requireNotNull(page))) throw AiTransportException(diagnostic = "model_catalog_pagination_loop")
        }
        throw AiTransportException(diagnostic = "model_catalog_page_limit")
    }

}
