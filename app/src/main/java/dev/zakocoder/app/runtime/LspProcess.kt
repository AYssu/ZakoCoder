package dev.zakocoder.app.runtime

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Binary stdio transport. In particular, never send PRoot/server stderr to JSON-RPC stdout. */
internal class LspProcess(
    private val command: RuntimeCommand,
    private val hostDirectory: File,
    private val errorOutput: (String) -> Unit = {},
) : Closeable {
    private val closed = AtomicBoolean()
    @Volatile private var process: Process? = null
    @Volatile private var ownedPid: Int? = null
    private val errors = StringBuilder()
    val isAlive: Boolean get() = !closed.get() && process?.isAlive == true
    val input: InputStream get() = process?.inputStream ?: throw IOException("Language server not started")
    val output: OutputStream get() = process?.outputStream ?: throw IOException("Language server not started")

    @Synchronized fun start() {
        check(!closed.get()) { "Language server connection closed" }
        check(process == null) { "Language server already started" }
        val builder = ProcessBuilder(listOf("/system/bin/sh", "-c", "printf '%s\\n' \"${'$'}${'$'}\"; exec \"${'$'}@\"", "zakocoder-lsp", command.executable) + command.arguments)
            .directory(hostDirectory).redirectErrorStream(false)
        builder.environment().clear()
        builder.environment().putAll(command.environment)
        val child = builder.start()
        process = child
        thread(name = "ZakoLsp-stderr", isDaemon = true) {
            try {
                child.errorStream.bufferedReader().use { reader ->
                    val buffer = CharArray(1024)
                    while (!closed.get()) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        val snapshot = synchronized(errors) {
                            errors.append(buffer, 0, count)
                            if (errors.length > 8000) errors.delete(0, errors.length - 8000)
                            errors.toString()
                        }
                        errorOutput(snapshot)
                    }
                }
            } catch (_: IOException) { /* The owner closing the process closes stderr. */ }
        }
        try {
            // Read just the trusted host wrapper's PID line. BufferedReader would consume and
            // lose the first Content-Length frame already emitted by the guest server.
            ownedPid = readOwnedLspPid(child.inputStream)
            if (closed.get()) throw IOException("Language server connection closed")
        } catch (error: Exception) {
            close()
            throw error
        }
    }
    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (process?.isAlive == true) ownedPid?.let(OwnedProcesses::terminate)
        process?.let { child ->
            runCatching { child.outputStream.close() }
            child.destroyForcibly()
            runCatching { child.inputStream.close() }
            runCatching { child.errorStream.close() }
        }
    }
}

internal fun readOwnedLspPid(input: InputStream): Int {
    val line = StringBuilder()
    while (line.length <= 12) {
        val byte = input.read()
        if (byte < 0) throw IOException("Language server wrapper ended before PID")
        if (byte == '\n'.code) return line.toString().toIntOrNull()?.takeIf { it > 0 }
            ?: throw IOException("Invalid owned language-server PID")
        if (byte !in '0'.code..'9'.code) throw IOException("Invalid owned language-server PID")
        line.append(byte.toChar())
    }
    throw IOException("Invalid owned language-server PID")
}
