package dev.zakocoder.app.diagnostics

import android.content.Context
import dev.zakocoder.app.ai.AiApiProtocol
import dev.zakocoder.app.ai.AiFailure
import dev.zakocoder.app.ai.AiPhase
import dev.zakocoder.app.ai.AiTool
import dev.zakocoder.app.ai.AiToolStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** No user-controlled text is accepted by the diagnostic event boundary. */
internal enum class DiagnosticStage {
    Request, Task, Tool, Service, SshResolve, SshConnect, SshHandshake, SshAuthenticate, SshCommand,
    McpPrepare, McpConnect, McpInitialize, McpCall, RuntimeInstall, Terminal, Browser, Screen, History, Startup
}
internal enum class DiagnosticOutcome { Started, Headers, FirstByte, Progress, Retry, Succeeded, Failed, Cancelled, Waiting, Changed }
internal enum class DiagnosticCode {
    Available, Unavailable, Denied, Disconnected, Timeout, Io, InvalidConfiguration, InvalidResponse, Interrupted, Recovered,
    AuthenticationFailed, HostKeyChanged, HostKeyUntrusted, HandshakeFailed, NameResolutionFailed, Storage, Limit,
    TlsFailed, ConnectFailed, StreamIncomplete, MalformedResponse,
    RepeatedToolFailure, TaskRequestLimit, TaskToolLimit, TaskTimeLimit, TaskTokenLimit
}

/** Only application-owned stop codes cross the export boundary, never diagnostic free text. */
internal fun taskStopDiagnosticCode(diagnostic: String): DiagnosticCode? = when (diagnostic.substringBefore('\n').substringBefore('\r')) {
    "task_budget:repeated_tool_failure", "task_budget:child_repeated_tool_failure" -> DiagnosticCode.RepeatedToolFailure
    "task_budget:requests" -> DiagnosticCode.TaskRequestLimit
    "task_budget:tools" -> DiagnosticCode.TaskToolLimit
    "task_budget:elapsed" -> DiagnosticCode.TaskTimeLimit
    "task_budget:tokens" -> DiagnosticCode.TaskTokenLimit
    else -> null
}

/** Locally generated correlation identifier; URLs, model names and external IDs cannot enter it. */
internal class DiagnosticId private constructor(internal val value: String) {
    companion object {
        fun create() = DiagnosticId(UUID.randomUUID().toString())
        internal fun parse(value: String): DiagnosticId? = runCatching {
            UUID.fromString(value).takeIf { it.toString() == value }?.let { DiagnosticId(value) }
        }.getOrNull()
    }
}

internal data class DiagnosticEvent(
    val stage: DiagnosticStage, val outcome: DiagnosticOutcome, val id: DiagnosticId,
    val owner: DiagnosticId? = null, val phase: AiPhase? = null, val tool: AiTool? = null,
    val toolStatus: AiToolStatus? = null, val failure: AiFailure? = null, val protocol: AiApiProtocol? = null,
    val code: DiagnosticCode? = null, val active: Boolean? = null,
    val turn: Int? = null, val round: Int? = null, val attempt: Int? = null,
    val httpStatus: Int? = null, val bytes: Long? = null, val events: Int? = null,
    val elapsedMillis: Long? = null, val delayMillis: Long? = null, val requestBytes: Int? = null,
    val inputTokens: Long? = null, val outputTokens: Long? = null, val cachedTokens: Long? = null,
    val operation: DiagnosticId? = null
)

internal data class DiagnosticSnapshot(val jsonl: String, val droppedEvents: Long, val writeFailures: Long)

/**
 * One bounded process-wide writer. Retains at most two 256 KiB JSONL files for seven days.
 * Producers never block a model/socket/UI callback. A full queue is counted, not grown.
 * Snapshot is a queued barrier, so active tasks can export their latest accepted events.
 */
internal class DiagnosticEvents private constructor(private val directory: File) {
    private sealed interface Operation {
        data class Append(val event: DiagnosticEvent, val time: Long) : Operation
        data class Snapshot(val result: CompletableDeferred<DiagnosticSnapshot>) : Operation
    }
    private val queue = Channel<Operation>(256)
    private val dropped = AtomicLong()
    private val failures = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val current get() = File(directory, "events.jsonl")
    private val previous get() = File(directory, "events.previous.jsonl")
    init {
        scope.launch {
            for (operation in queue) {
                when (operation) {
                    is Operation.Append -> try { append(operation) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: IOException) { failures.incrementAndGet() }
                    catch (_: SecurityException) { failures.incrementAndGet() }
                    is Operation.Snapshot -> try { operation.result.complete(snapshot()) }
                    catch (cancelled: CancellationException) { operation.result.cancel(cancelled); throw cancelled }
                    catch (_: IOException) { failures.incrementAndGet(); operation.result.complete(DiagnosticSnapshot("", dropped.get(), failures.get())) }
                    catch (_: SecurityException) { failures.incrementAndGet(); operation.result.complete(DiagnosticSnapshot("", dropped.get(), failures.get())) }
                }
            }
        }
    }

    fun record(event: DiagnosticEvent) {
        if (!queue.trySend(Operation.Append(event, System.currentTimeMillis())).isSuccess) dropped.incrementAndGet()
    }

    /** Export never waits indefinitely behind storage IO. No request body or historic raw log is read. */
    suspend fun exportSnapshot(): DiagnosticSnapshot = withTimeout(5_000) {
        val result = CompletableDeferred<DiagnosticSnapshot>()
        queue.send(Operation.Snapshot(result))
        result.await()
    }

    private fun append(operation: Operation.Append) {
        checkedDirectory(create = true)
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        // Validate both leaves before any rotation/deletion; never touch a link's target.
        listOf(previous, current).forEach(::checkedFile)
        listOf(previous, current).forEach { file ->
            checkedFile(file)?.let { attributes ->
                if (attributes.lastModifiedTime().toMillis() < cutoff) Files.delete(file.toPath())
            }
        }
        val bytes = (encode(operation.event, operation.time).toString() + "\n").toByteArray(Charsets.UTF_8)
        if ((checkedFile(current)?.size() ?: 0) + bytes.size > MAX_FILE_BYTES) {
            if (checkedFile(previous) != null) Files.delete(previous.toPath())
            if (checkedFile(current) != null) Files.move(current.toPath(), previous.toPath())
        }
        checkedDirectory(create = false)
        Files.newByteChannel(current.toPath(), setOf(WRITE, CREATE, APPEND, NOFOLLOW_LINKS)).use { channel ->
            if (channel.size() + bytes.size > MAX_FILE_BYTES) throw IOException("diagnostic_size")
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }

    private suspend fun snapshot(): DiagnosticSnapshot {
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        val lines = StringBuilder()
        if (!checkedDirectory(create = false)) return DiagnosticSnapshot("", dropped.get(), failures.get())
        listOf(previous, current).forEach(::checkedFile)
        for (file in listOf(previous, current)) {
            currentCoroutineContext().ensureActive()
            val attributes = checkedFile(file) ?: continue
            if (attributes.lastModifiedTime().toMillis() < cutoff) {
                Files.delete(file.toPath())
                continue
            }
            // Reconstruct the whitelist even if an older version or a corrupt file inserted fields.
            checkedDirectory(create = false)
            val content = Files.newByteChannel(file.toPath(), setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                if (channel.size() > MAX_FILE_BYTES) throw IOException("diagnostic_size")
                val buffer = ByteBuffer.allocate(MAX_FILE_BYTES.toInt() + 1)
                while (buffer.hasRemaining() && channel.read(buffer) != -1) currentCoroutineContext().ensureActive()
                if (buffer.position() > MAX_FILE_BYTES) throw IOException("diagnostic_size")
                String(buffer.array(), 0, buffer.position(), Charsets.UTF_8)
            }
            content.lineSequence().forEach { line ->
                currentCoroutineContext().ensureActive()
                if (line.length <= MAX_LINE_CHARS) decode(line, cutoff)?.let { lines.append(it).append('\n') }
            }
        }
        return DiagnosticSnapshot(lines.toString(), dropped.get(), failures.get())
    }

    private fun attributes(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }

    private fun checkedDirectory(create: Boolean): Boolean {
        var value = attributes(directory)
        if (value == null && create) {
            Files.createDirectory(directory.toPath())
            value = attributes(directory)
        }
        if (value == null) return false
        if (!value.isDirectory || value.isSymbolicLink) throw IOException("diagnostic_directory")
        return true
    }

    private fun checkedFile(file: File): BasicFileAttributes? = attributes(file)?.also {
        if (!it.isRegularFile || it.isSymbolicLink) throw IOException("diagnostic_file")
        if (it.size() > MAX_FILE_BYTES) throw IOException("diagnostic_size")
    }

    companion object {
        const val MAX_FILE_BYTES = 256 * 1024L
        const val RETENTION_MILLIS = 7 * 24 * 60 * 60 * 1000L
        private const val MAX_LINE_CHARS = 2_048
        @Volatile private var instance: DiagnosticEvents? = null
        fun get(context: Context): DiagnosticEvents = instance ?: synchronized(this) {
            instance ?: DiagnosticEvents(File(context.applicationContext.noBackupFilesDir, "diagnostic-events-v1")).also { instance = it }
        }
        internal fun forTests(directory: File) = DiagnosticEvents(directory)

        internal fun encode(event: DiagnosticEvent, time: Long): JSONObject = JSONObject().apply {
            put("time", time); put("stage", event.stage.name); put("outcome", event.outcome.name); put("id", event.id.value)
            event.owner?.let { put("owner", it.value) }
            event.operation?.let { put("operation", it.value) }
            mapOf("phase" to event.phase, "tool" to event.tool, "toolStatus" to event.toolStatus,
                "failure" to event.failure, "protocol" to event.protocol, "code" to event.code).forEach { (key, value) -> value?.let { put(key, it.name) } }
            event.active?.let { put("active", it) }
            mapOf("turn" to event.turn, "round" to event.round, "attempt" to event.attempt, "httpStatus" to event.httpStatus,
                "bytes" to event.bytes, "events" to event.events, "elapsedMillis" to event.elapsedMillis,
                "delayMillis" to event.delayMillis, "requestBytes" to event.requestBytes, "inputTokens" to event.inputTokens,
                "outputTokens" to event.outputTokens, "cachedTokens" to event.cachedTokens).forEach { (key, value) ->
                value?.toLong()?.takeIf { it >= 0 }?.let { put(key, it) }
            }
        }

        internal fun decode(line: String, cutoff: Long): JSONObject? = try {
            val input = JSONObject(line)
            val time = input.getLong("time")
            if (time < cutoff || time > System.currentTimeMillis() + 60_000) null else {
                val id = DiagnosticId.parse(input.getString("id"))
                if (id == null) null else {
                    val event = DiagnosticEvent(DiagnosticStage.valueOf(input.getString("stage")), DiagnosticOutcome.valueOf(input.getString("outcome")), id,
                        owner = input.optString("owner").takeIf { it.isNotEmpty() }?.let(DiagnosticId::parse),
                        phase = enumValue<AiPhase>(input, "phase"), tool = enumValue<AiTool>(input, "tool"),
                        toolStatus = enumValue<AiToolStatus>(input, "toolStatus"), failure = enumValue<AiFailure>(input, "failure"),
                        protocol = enumValue<AiApiProtocol>(input, "protocol"), code = enumValue<DiagnosticCode>(input, "code"),
                        active = input.opt("active") as? Boolean,
                        operation = input.optString("operation").takeIf { it.isNotEmpty() }?.let(DiagnosticId::parse))
                    encode(event, time).apply {
                        for (key in listOf("turn", "round", "attempt", "httpStatus", "bytes", "events", "elapsedMillis", "delayMillis", "requestBytes", "inputTokens", "outputTokens", "cachedTokens")) {
                            val value = input.opt(key) as? Number
                            value?.toLong()?.takeIf { it >= 0 }?.let { put(key, it) }
                        }
                    }
                }
            }
        } catch (_: org.json.JSONException) { null } catch (_: IllegalArgumentException) { null }

        private inline fun <reified T : Enum<T>> enumValue(input: JSONObject, key: String): T? =
            input.optString(key).takeIf { it.isNotEmpty() }?.let { value -> enumValues<T>().firstOrNull { it.name == value } }
    }
}
