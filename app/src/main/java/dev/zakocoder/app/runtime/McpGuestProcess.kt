package dev.zakocoder.app.runtime

import android.content.Context
import dev.zakocoder.app.mcp.McpServer
import dev.zakocoder.app.settings.AppSettings
import dev.zakocoder.app.workflow.shellArgument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A configured MCP program runs in the selected project's guest, never the Android shell. */
internal suspend fun LinuxRuntime.startMcpProcess(context: Context, projectId: String,
    settings: AppSettings, server: McpServer): Process = withContext(Dispatchers.IO) {
    require(isInstalled())
    require(server.command.isNotBlank() && !server.command.startsWith('-'))
    val environment = server.env.map { (key, value) ->
        require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
        shellArgument("$key=$value")
    }
    val script = "exec /usr/bin/env -- " + (environment + shellArgument(server.command) + server.args.map(::shellArgument)).joinToString(" ")
    val prepared = command(projectId, settings, script)
    // Only this fixed wrapper is parsed by the host shell. Guest script/configuration remains argv.
    val builder = ProcessBuilder(listOf("/system/bin/sh", "-c", "printf '%s\\n' \"${'$'}${'$'}\"; exec \"${'$'}@\"",
        "zakocoder-mcp", prepared.executable) + prepared.arguments).directory(context.filesDir)
    builder.environment().clear()
    builder.environment().putAll(prepared.environment)
    val process = builder.start()
    try {
        // Do not buffer this read: bytes after the PID belong to the JSON-RPC transport.
        val prefix = StringBuilder()
        while (true) {
            val value = process.inputStream.read()
            check(value >= 0 && prefix.length < 16)
            if (value == 10) break
            check(value in 48..57)
            prefix.append(value.toChar())
        }
        val pid = prefix.toString().toInt()
        val owned = OwnedMcpProcess(process, pid)
        try { currentCoroutineContext().ensureActive() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { owned.destroyForcibly(); throw cancelled }
        owned
    } catch (error: Exception) {
        process.destroyForcibly()
        throw error
    }
}

/** Closing the protocol also closes only its owned guest process tree. */
private class OwnedMcpProcess(private val delegate: Process, private val ownedPid: Int) : Process() {
    private val closed = AtomicBoolean()
    override fun getOutputStream() = delegate.outputStream
    override fun getInputStream() = delegate.inputStream
    override fun getErrorStream() = delegate.errorStream
    override fun waitFor() = delegate.waitFor()
    override fun waitFor(timeout: Long, unit: TimeUnit) = delegate.waitFor(timeout, unit)
    override fun exitValue() = delegate.exitValue()
    override fun isAlive() = delegate.isAlive
    override fun destroy() {
        if (closed.compareAndSet(false, true)) {
            if (delegate.isAlive) OwnedProcesses.terminate(ownedPid)
            delegate.destroy()
        }
    }
    override fun destroyForcibly(): Process { destroy(); delegate.destroyForcibly(); return this }
}
