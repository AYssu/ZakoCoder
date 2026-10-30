package dev.zakocoder.app.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSource
import okio.buffer
import okio.source
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Implements MCP 2025-06-18 / 2025-03-26 and legacy 2024-11-05 SSE; no OAuth or server requests. */
internal class McpSession(
    private val server: McpServer, private val scope: CoroutineScope,
    private val start: suspend () -> Process,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).callTimeout(65, TimeUnit.SECONDS).build(),
) {
    companion object { const val MAX_MESSAGE = 2L * 1024 * 1024; const val REQUEST_TIMEOUT = 70_000L }
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val ids = AtomicLong()
    private val messages = Channel<JSONObject>(64)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val responses = ConcurrentHashMap.newKeySet<Response>()
    private val readers = java.util.concurrent.CopyOnWriteArrayList<Job>()
    @Volatile private var process: Process? = null
    private val endpoint = CompletableDeferred<String>()
    private var sessionId: String? = null
    private var initialized = false
    private var negotiated = false
    private var version = "2025-06-18"
    private var capabilities = JSONObject()

    suspend fun exchange(method: String, params: JSONObject): JSONObject = mutex.withLock {
        checkOpen()
        try {
            withTimeout(REQUEST_TIMEOUT) {
                if (!initialized) initialize()
                val capability = method.substringBefore('/')
                if (!capabilities.has(capability)) throw McpException(McpProblem.Unavailable)
                roundTrip(method, params)
            }
        } catch (cancelled: CancellationException) {
            close()
            if (cancelled is TimeoutCancellationException) throw McpException(McpProblem.Timeout)
            throw cancelled
        } catch (failure: McpException) { close(); throw failure }
        catch (_: Exception) { close(); throw McpException(McpProblem.Unavailable) }
    }
    suspend fun probe(): JSONObject = mutex.withLock {
        checkOpen()
        try {
            withTimeout(REQUEST_TIMEOUT) {
                if (!initialized) initialize()
                JSONObject().put("protocolVersion", version).put("capabilities", JSONObject(capabilities.toString()))
            }
        } catch (cancelled: CancellationException) {
            close(); if (cancelled is TimeoutCancellationException) throw McpException(McpProblem.Timeout); throw cancelled
        } catch (failure: McpException) { close(); throw failure }
        catch (_: Exception) { close(); throw McpException(McpProblem.Unavailable) }
    }
    private suspend fun initialize() {
        when (server.transport) {
            McpTransport.Stdio -> {
                val launched = withContext(Dispatchers.IO) { start().also { process = it; if (closed.get()) it.destroyForcibly() } }
                if (closed.get()) { withContext(Dispatchers.IO) { launched.destroyForcibly() }; checkOpen() }
                launchReader {
                    try {
                        val source = launched.inputStream.source().buffer()
                        while (isActive && !closed.get()) {
                            val line = source.readUtf8LineStrict(MAX_MESSAGE)
                            if (line.isNotBlank()) publish(parseMessage(line))
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { messages.close(failure as? McpException ?: McpException(McpProblem.Unavailable)) }
                }
                launchReader {
                    // Drain stderr without retaining or logging environment, tokens, or server errors.
                    try { val buffer = ByteArray(4096); while (isActive && launched.errorStream.read(buffer) >= 0) Unit }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* close terminates the owned pipe */ }
                }
            }
            McpTransport.Sse -> {
                launchReader {
                    try {
                        val response = executeAsync(request(server.url).get().build())
                        checkHttp(response)
                        if (response.header("Content-Type")?.substringBefore(';') != "text/event-stream") throw McpException(McpProblem.Protocol)
                        readEvents(response, true)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        val diagnostic = failure as? McpException ?: (failure as? java.io.IOException)?.let(McpDiagnostics::network) ?: McpException(McpProblem.Protocol)
                        endpoint.completeExceptionally(diagnostic); messages.close(diagnostic)
                    }
                }
                endpoint.await()
            }
            McpTransport.StreamableHttp -> Unit
        }
        val result = roundTrip("initialize", JSONObject().put("protocolVersion", version).put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "ZakoCoder").put("version", "0.1.0")))
        version = result.optString("protocolVersion")
        if (version !in setOf("2025-06-18", "2025-03-26", "2024-11-05")) throw McpException(McpProblem.Protocol)
        capabilities = result.optJSONObject("capabilities") ?: throw McpException(McpProblem.Protocol)
        negotiated = true
        send(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
        initialized = true
    }
    private suspend fun roundTrip(method: String, params: JSONObject): JSONObject {
        val id = ids.incrementAndGet()
        send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
        while (true) {
            val incoming = messages.receive()
            if (incoming.opt("id") is Number && incoming.getLong("id") == id && !incoming.has("method")) {
                if (incoming.has("error")) throw McpException(McpProblem.Protocol)
                return incoming.optJSONObject("result") ?: throw McpException(McpProblem.Protocol)
            }
            // We advertise no roots/sampling/elicitation. Explicitly reject requests rather than execute callbacks.
            if (incoming.has("method") && incoming.has("id")) send(JSONObject().put("jsonrpc", "2.0").put("id", incoming.get("id"))
                .put("error", JSONObject().put("code", -32601).put("message", "Client capability unavailable")))
        }
    }
    private suspend fun send(message: JSONObject) {
        checkOpen()
        val text = message.toString()
        val expectsResponse = message.has("id") && message.has("method")
        if (text.toByteArray().size > MAX_MESSAGE) throw McpException(McpProblem.Limit)
        if (server.transport == McpTransport.Stdio) {
            io { process!!.outputStream.apply { write((text + "\n").toByteArray()); flush() } }
            return
        }
        val target = if (server.transport == McpTransport.Sse) endpoint.await() else server.url
        val response = executeAsync(request(target).post(text.toRequestBody("application/json".toMediaType())).build())
        try {
            checkHttp(response)
            response.header("Mcp-Session-Id")?.let { value ->
                if (value.length !in 1..1024 || value.any { it.code !in 33..126 } || sessionId != null && sessionId != value) throw McpException(McpProblem.Protocol)
                sessionId = value
            }
            if (server.transport == McpTransport.Sse || !expectsResponse) return
            when (response.header("Content-Type")?.substringBefore(';')?.trim()) {
                "application/json" -> io {
                    val body = response.body ?: throw McpException(McpProblem.Protocol)
                    val source = body.source()
                    val out = okio.Buffer()
                    while (true) {
                        val count = source.read(out, minOf(8192L, MAX_MESSAGE + 1 - out.size))
                        if (count < 0) break
                        if (out.size > MAX_MESSAGE) throw McpException(McpProblem.Limit)
                    }
                    val raw = out.readUtf8()
                    publish(parseMessage(raw))
                }
                "text/event-stream" -> {
                    // Keep reading concurrently so a response can arrive before the SSE stream closes.
                    launchReader {
                        try { readEvents(response, false) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { messages.close(failure as? McpException ?: (failure as? java.io.IOException)?.let(McpDiagnostics::network) ?: McpException(McpProblem.Protocol)) }
                    }
                    return
                }
                else -> throw McpException(McpProblem.Protocol)
            }
        } finally {
            // An SSE body belongs to its reader until EOF/close; other responses are consumed here.
            if (server.transport == McpTransport.Sse || response.header("Content-Type")?.substringBefore(';')?.trim() != "text/event-stream" || !expectsResponse) release(response)
        }
    }
    private fun checkHttp(response: Response) {
        if (response.code in 300..399) throw McpException(McpProblem.Unavailable, McpFailureReason.Redirect)
        if (!response.isSuccessful) throw McpException(McpProblem.Unavailable, McpFailureReason.HttpStatus, response.code)
    }
    private fun request(target: String): Request.Builder {
        if (!McpConfigParser.sameOrigin(server.url, target)) throw McpException(McpProblem.Protocol)
        return Request.Builder().url(target).header("Accept", "application/json, text/event-stream")
            .apply {
                server.headers.forEach { (name, value) -> header(name, value) }
                if (negotiated) header("MCP-Protocol-Version", version)
                sessionId?.let { header("Mcp-Session-Id", it) }
            }
    }
    private fun launchReader(block: suspend CoroutineScope.() -> Unit) {
        val reader = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY, block = block)
        readers += reader
        reader.invokeOnCompletion { readers -= reader }
        reader.start()
    }
    private suspend fun <T> io(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        val job = scope.launch(Dispatchers.IO) {
            try {
                val result = block()
                if (continuation.isActive) continuation.resume(result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure as? McpException ?: (failure as? java.io.IOException)?.let(McpDiagnostics::network) ?: McpException(McpProblem.Unavailable))
            }
        }
        continuation.invokeOnCancellation {
            job.cancel()
            scope.launch(NonCancellable + Dispatchers.IO) { close() }
        }
    }
    private suspend fun executeAsync(request: Request): Response = suspendCancellableCoroutine { continuation ->
        checkOpen()
        val call = client.newCall(request); calls += call
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, error: java.io.IOException) {
                calls -= call
                if (continuation.isActive) continuation.resumeWithException(McpDiagnostics.network(error))
            }
            override fun onResponse(call: Call, response: Response) {
                calls -= call; responses += response
                if (!continuation.isActive || closed.get()) releaseFromCallback(response)
                else continuation.resume(response) { _, value, _ -> releaseFromCallback(value) }
            }
        })
    }
    private suspend fun readEvents(response: Response, legacy: Boolean) {
        try {
            val source: BufferedSource = response.body?.source() ?: throw McpException(McpProblem.Protocol)
            var event = "message"; val data = StringBuilder(); var budget = 0L
            while (!closed.get()) {
                if (source.exhausted()) {
                    if (legacy) throw McpException(McpProblem.Unavailable)
                    break
                }
                val line = source.readUtf8LineStrict(MAX_MESSAGE)
                budget += line.toByteArray().size + 1
                if (budget > MAX_MESSAGE) throw McpException(McpProblem.Limit)
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) {
                            val payload = data.toString().removeSuffix("\n")
                            if (legacy && event == "endpoint") {
                                val address = java.net.URI(server.url).resolve(payload).toString()
                                if (!McpConfigParser.sameOrigin(server.url, address)) throw McpException(McpProblem.Protocol)
                                endpoint.complete(address)
                            } else if (event == "message") {
                                val incoming = parseMessage(payload); publish(incoming)
                                if (!legacy && incoming.has("id") && !incoming.has("method")) return
                            }
                        }
                        data.setLength(0); event = "message"; budget = 0
                    }
                    line.startsWith("event:") -> event = line.substring(6).removePrefix(" ")
                    line.startsWith("data:") -> data.append(line.substring(5).removePrefix(" ")).append('\n')
                }
            }
        } finally { release(response) }
    }
    private fun parseMessage(raw: String): JSONObject = try {
        McpJson.check(raw)
        JSONObject(raw)
    } catch (_: Exception) { throw McpException(McpProblem.Protocol) }
    private fun publish(message: JSONObject) {
        if (message.optString("jsonrpc") != "2.0") throw McpException(McpProblem.Protocol)
        if (!messages.trySend(message).isSuccess) throw McpException(McpProblem.Limit)
    }
    // Closing an unread error body can drain the socket. Keep cleanup off Main and never let
    // a cleanup exception replace the useful HTTP/protocol failure we are already reporting.
    private suspend fun release(response: Response) = withContext(NonCancellable + Dispatchers.IO) {
        responses -= response
        runCatching { response.close() }
        Unit
    }
    private fun releaseFromCallback(response: Response) {
        scope.launch(NonCancellable + Dispatchers.IO) { release(response) }
    }
    private fun checkOpen() { if (closed.get()) throw McpException(McpProblem.Closed) }
    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        withContext(NonCancellable + Dispatchers.IO) {
            calls.forEach { it.cancel() }
            responses.forEach { it.close() }; responses.clear()
            process?.let { child ->
                child.destroy(); if (child.isAlive) child.destroyForcibly()
                runCatching { child.outputStream.close() }; runCatching { child.inputStream.close() }; runCatching { child.errorStream.close() }
            }
            readers.forEach { it.cancel() }
            endpoint.cancel(); messages.close(McpException(McpProblem.Closed))
        }
    }
}
