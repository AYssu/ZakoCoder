package dev.zakocoder.app.settings

import android.content.Context
import android.util.AtomicFile
import dev.zakocoder.app.diagnostics.DiagnosticCode
import dev.zakocoder.app.diagnostics.DiagnosticEvent
import dev.zakocoder.app.diagnostics.DiagnosticEvents
import dev.zakocoder.app.diagnostics.DiagnosticId
import dev.zakocoder.app.diagnostics.DiagnosticOutcome
import dev.zakocoder.app.diagnostics.DiagnosticStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal enum class BackgroundTaskEvent { ServiceRequested, ServiceReady, ServiceStartRejected, ServiceDestroyedUnexpectedly, ServiceReleased, TaskPhase }

/** Private bounded operational evidence, exclusively fixed enum values and primitive counters. */
internal class BackgroundTaskDiagnostics(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "background-task-diagnostic.log"))
    private val mutex = Mutex()
    private val diagnostics = DiagnosticEvents.get(context)
    private var diagnosticId = DiagnosticId.create()
    suspend fun record(event: BackgroundTaskEvent, task: AiBackgroundTask? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (event == BackgroundTaskEvent.ServiceRequested) diagnosticId = DiagnosticId.create()
            val (outcome, code) = when (event) {
                BackgroundTaskEvent.ServiceRequested -> DiagnosticOutcome.Started to null
                BackgroundTaskEvent.ServiceReady -> DiagnosticOutcome.Succeeded to DiagnosticCode.Available
                BackgroundTaskEvent.ServiceStartRejected -> DiagnosticOutcome.Failed to DiagnosticCode.Denied
                BackgroundTaskEvent.ServiceDestroyedUnexpectedly -> DiagnosticOutcome.Failed to DiagnosticCode.Interrupted
                BackgroundTaskEvent.ServiceReleased -> DiagnosticOutcome.Changed to DiagnosticCode.Disconnected
                BackgroundTaskEvent.TaskPhase -> DiagnosticOutcome.Changed to null
            }
            diagnostics.record(DiagnosticEvent(
                DiagnosticStage.Service, outcome, diagnosticId, code = code, phase = task?.phase,
                active = task?.active, round = task?.round, events = task?.tools,
            ))
            try {
                val existing = if (file.baseFile.exists()) file.openRead().bufferedReader().useLines { lines ->
                    lines.takeLastBounded(127)
                } else emptyList()
                val line = "${System.currentTimeMillis()} event=${event.name}" +
                    (task?.let { " phase=${it.phase.name} active=${it.active} round=${it.round} tools=${it.tools}" } ?: "")
                val output = file.startWrite()
                try { output.write((existing + line).joinToString("\n", postfix = "\n").toByteArray()); file.finishWrite(output) }
                catch (failure: Exception) { file.failWrite(output); throw failure }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: java.io.IOException) { /* Diagnostics must never interrupt an otherwise valid task. */ }
        }
    }
    private fun Sequence<String>.takeLastBounded(size: Int): List<String> {
        val lines = ArrayDeque<String>()
        take(256).forEach { line -> if (lines.size == size) lines.removeFirst(); lines.addLast(line.take(256)) }
        return lines.toList()
    }
}
