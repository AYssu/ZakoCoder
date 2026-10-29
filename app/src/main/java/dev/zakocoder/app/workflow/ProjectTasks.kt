package dev.zakocoder.app.workflow

import android.content.Context
import android.util.AtomicFile
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiLogRedactor
import dev.zakocoder.app.ai.AiProjectFiles
import dev.zakocoder.app.runtime.CommandResult
import dev.zakocoder.app.runtime.PrivateFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

fun shellArgument(value: String): String = "'" + value.replace("'", "'\\''") + "'"
data class ProjectCommand(val kind: String, val script: String, val cwd: String = "", val timeoutSeconds: Int = 1800) {
    fun shell(): String = if (cwd.isBlank()) script else "cd -- ${shellArgument(cwd)} &&\n$script"
}
data class ProjectRun(val id: String, val kind: String, val script: String, val started: Long, val status: String = "running", val exit: Int? = null,
    val elapsed: Long = 0, val truncated: Boolean = false, val conversation: String = "", val turn: Int = 0, val source: String = "user", val outputChars: Long = 0,
    val sourceBefore: ProjectSourceStamp? = null, val sourceAfter: ProjectSourceStamp? = null, val timeoutMillis: Long = 0)
class ProjectCommandFailure(val runId: String, val timedOut: Boolean, cause: Exception) : Exception("Recorded project command failed", cause)

data class ProjectTaskState(val projectId: String? = null, val commands: List<ProjectCommand> = emptyList(), val runs: List<ProjectRun> = emptyList(),
    val loading: Boolean = false, val busy: Boolean = false, val saving: Boolean = false, val error: String = "", val log: String = "",
    val currentSource: ProjectSourceStamp? = null, val sourceCheckedAt: Long = 0, val sourceChecking: Boolean = false)

/** User-configured guest commands and actual execution evidence. No model declarations become test results. */
class ProjectTasks(private val context: Context, private val root: (String) -> File,
    private val execute: suspend (String, String, Long, (String) -> Unit) -> CommandResult,
    private val available: () -> Boolean, private val activity: () -> Unit,
    private val captureSource: suspend (String) -> ProjectSourceStamp = { id -> ProjectSourceEvidence(root).capture(id, currentCoroutineContext()) }) {
    private val base = File(context.noBackupFilesDir, "project-tasks-v1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(ProjectTaskState())
    val state = mutable.asStateFlow()
    private var task: Job? = null
    @Volatile private var activeRunId: String? = null
    private val evidenceGeneration = java.util.concurrent.atomic.AtomicLong()
    private fun invalidateEvidence(): Long {
        val generation = evidenceGeneration.incrementAndGet()
        mutable.update { if (generation == evidenceGeneration.get()) it.copy(currentSource = null, sourceCheckedAt = 0, sourceChecking = false) else it }
        return generation
    }
    private fun dir(id: String) = File(base, MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }).apply { mkdirs() }
    fun deleteProject(id: String) { dir(id).deleteRecursively() }
    private fun read(file: File): JSONObject? = if (!file.exists()) null else AtomicFile(file).openRead().use { require(it.available() <= 1024 * 1024); JSONObject(it.bufferedReader().readText()) }
    private fun write(file: File, json: JSONObject) {
        val atomic = AtomicFile(file); val out = atomic.startWrite()
        try { out.write(json.toString().toByteArray()); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
    }
    fun commands(id: String): List<ProjectCommand> {
        val array = read(File(dir(id), "commands.json"))?.getJSONArray("commands") ?: return emptyList()
        require(array.length() <= 3)
        return (0 until array.length()).map { array.getJSONObject(it).let { j -> ProjectCommand(j.getString("kind"), j.getString("script"), j.getString("cwd"), j.getInt("timeout")) } }.onEach { validate(id, it) }
    }
    private fun validate(id: String, c: ProjectCommand, existingCwd: Boolean = false) {
        require(c.kind in setOf("build", "test", "run") && c.script.isNotBlank() && c.script.length <= 16000 && '\u0000' !in c.script && c.timeoutSeconds in 1..86400)
        if (c.cwd.isNotBlank()) { val file = AiProjectFiles(root).resolve(id, c.cwd); require(!existingCwd || file.isDirectory) }
    }
    fun save(command: ProjectCommand, onSaved: (Boolean) -> Unit = {}) {
        val id = mutable.value.projectId ?: return
        if (mutable.value.busy || mutable.value.loading || mutable.value.saving || !available()) {
            mutable.update { it.copy(error = context.getString(R.string.project_other_busy)) }; onSaved(false); return
        }
        mutable.update { it.copy(saving = true, error = "") }
        scope.launch {
            try {
                val all = withContext(Dispatchers.IO) {
                    validate(id, command, true)
                    val all = commands(id).filterNot { it.kind == command.kind } + command
                    write(File(dir(id), "commands.json"), JSONObject().put("commands", JSONArray(all.map { JSONObject().put("kind", it.kind).put("script", it.script).put("cwd", it.cwd).put("timeout", it.timeoutSeconds) })))
                    all
                }
                if (mutable.value.projectId == id) { mutable.update { it.copy(commands = all, saving = false, error = "") }; onSaved(true) }
                else onSaved(false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (mutable.value.projectId == id) mutable.update { it.copy(saving = false, error = context.getString(R.string.project_command_invalid)) }; onSaved(false) }
        }
    }
    private fun encode(r: ProjectRun) = JSONObject().put("id", r.id).put("kind", r.kind).put("script", AiLogRedactor("").clean(r.script)).put("started", r.started).put("status", r.status).put("exit", r.exit).put("elapsed", r.elapsed)
        .put("truncated", r.truncated).put("conversation", r.conversation).put("turn", r.turn).put("source", r.source).put("outputChars", r.outputChars)
        .put("sourceBefore", r.sourceBefore?.encode()).put("sourceAfter", r.sourceAfter?.encode()).put("timeoutMillis", r.timeoutMillis)
    fun runs(id: String): List<ProjectRun> {
        val a = read(File(dir(id), "runs.json"))?.getJSONArray("runs") ?: return emptyList(); require(a.length() <= 20)
        return (0 until a.length()).map { a.getJSONObject(it).let { j -> ProjectRun(j.getString("id"), j.getString("kind"), j.getString("script"), j.getLong("started"), j.getString("status").let { if (it == "running" && j.getString("id") != activeRunId) "interrupted" else it }, if (j.isNull("exit")) null else j.getInt("exit"), j.getLong("elapsed"), j.getBoolean("truncated"), j.optString("conversation"), j.optInt("turn"), j.optString("source", "user"), j.optLong("outputChars", 0), ProjectSourceStamp.decode(j.optJSONObject("sourceBefore")), ProjectSourceStamp.decode(j.optJSONObject("sourceAfter")), j.optLong("timeoutMillis", 0)) } }
    }
    private fun record(id: String, run: ProjectRun) {
        val records = (runs(id).filterNot { it.id == run.id } + run).sortedByDescending { it.started }
        write(File(dir(id), "runs.json"), JSONObject().put("runs", JSONArray(records.take(20).map(::encode))))
        records.drop(20).forEach { File(dir(id), it.id + ".log").delete() }
    }
    fun select(id: String) {
        if (mutable.value.busy || mutable.value.saving) return
        invalidateEvidence()
        mutable.value = ProjectTaskState(projectId = id, loading = true)
        scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { commands(id) to runs(id) }
                if (mutable.value.projectId == id) mutable.update { it.copy(commands = data.first, runs = data.second, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (mutable.value.projectId == id) mutable.update { it.copy(loading = false, error = context.getString(R.string.project_tasks_load_error)) } }
        }
    }
    suspend fun modelContext(id: String, inspectSource: Boolean = true, unsavedFiles: Int = 0): String = withContext(Dispatchers.IO) {
        val configured = commands(id)
        val recent = runs(id).take(5)
        val current = if (recent.isEmpty() || !inspectSource) null else captureSource(id)
        "[Current editor state: unsaved_files=${unsavedFiles.coerceIn(0, 8)} in loaded editor tabs for this project; zero does not prove no other private draft exists. Commands and source evidence refer only to on-disk workspace files, never unsaved editor buffers or private drafts. A successful disk command does not validate unsaved edits. No draft body is included here. Do not claim unsaved work was saved, tested or merged. Saving requires the user's existing editor action; never discard or overwrite a buffer to bypass a conflict.]\n\n" +
        configured.joinToString("\n\n", prefix = "Configured project validation commands (reference, not execution permission). Use existing run_command only after applicable approval; report unrun checks honestly. Execute each configured validation command verbatim in its own run_command call, without wrappers, prefixes, suffixes or chaining it with other commands. Only an exact standalone match is recorded as that configured test/build kind; combined or altered scripts remain generic commands, whose exit cannot prove each check passed. Run diagnostics separately.\n") { "${it.kind}; timeout_seconds=${it.timeoutSeconds}; exact shell command:\n${it.shell()}" } +
            "\n\n[Recent recorded command evidence; sampled_at=${System.currentTimeMillis()}; source scope excludes credentials, links, dependencies, generated/cache directories, editor/VCS metadata and external state. Unchanged means only the bounded sampled scope; it is not full verification or proof of no transient edits. Changed, ChangedDuringRun or Unknown cannot establish that current sources were verified. A changed command configuration also requires fresh evidence. Preserve actual exits and distinguish these limits in your report. Never replay automatically.]\n" +
            JSONArray(recent.map { r -> JSONObject().put("run_id", r.id).put("kind", r.kind).put("script", r.script.take(2000)).put("script_truncated", r.script.length > 2000)
                .put("status", r.status).put("exit_code", r.exit ?: JSONObject.NULL).put("started", r.started).put("source_verification", r.verification(current).name)
                .put("command_configuration", r.configuration(configured)).put("source_files", current?.files ?: JSONObject.NULL).put("source_limited", current?.limited ?: true) })
    }
    fun refreshEvidence(): Job? {
        val id = mutable.value.projectId ?: return null
        if (mutable.value.sourceChecking || mutable.value.loading) return null
        val generation = evidenceGeneration.incrementAndGet()
        mutable.update { if (it.projectId == id && generation == evidenceGeneration.get()) it.copy(currentSource = null, sourceCheckedAt = 0, sourceChecking = true) else it }
        return scope.launch {
            try {
                val stamp = withContext(Dispatchers.IO) { captureSource(id) }
                mutable.update { if (it.projectId == id && generation == evidenceGeneration.get()) it.copy(currentSource = stamp, sourceCheckedAt = System.currentTimeMillis()) else it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Unknown current scope must not reuse a previous successful sample. */ }
            finally { mutable.update { if (it.projectId == id && generation == evidenceGeneration.get()) it.copy(sourceChecking = false) else it } }
        }
    }
    fun run(command: ProjectCommand, conversation: String = "", turn: Int = 0) {
        val id = mutable.value.projectId ?: return
        if (mutable.value.busy || mutable.value.saving || mutable.value.loading || !available()) return
        mutable.update { it.copy(busy = true, log = "", error = "") }; activity()
        task = scope.launch {
            try { withContext(Dispatchers.IO) { validate(id, command, true) }; observed(id, command.shell(), command.timeoutSeconds.toLong() * 1000, {}, conversation, turn) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = context.getString(R.string.project_run_error)) } }
            finally { mutable.update { it.copy(busy = false) }; activity() }
        }
    }
    fun stop() { task?.cancel() }
    suspend fun observed(id: String, script: String, timeout: Long, output: (String) -> Unit, conversation: String = "", turn: Int = 0, source: String = "user", inspectSource: () -> Boolean = { true },
        executeCommand: suspend (String, String, Long, (String) -> Unit) -> CommandResult = execute): CommandResult {
        require(script.isNotBlank() && script.length <= 18000 && timeout in 1000..86400000)
        // Dependency installation is execution evidence, never a configured validation result.
        val matched = if (source == "dependency") null else withContext(Dispatchers.IO) { commands(id).firstOrNull { it.shell() == script } }
        invalidateEvidence()
        val before = withContext(Dispatchers.IO) { if (inspectSource()) captureSource(id) else null }
        var run = ProjectRun(UUID.randomUUID().toString(), if (source == "dependency") "dependency" else matched?.kind ?: "command", script, System.currentTimeMillis(), conversation = conversation, turn = turn, source = source, sourceBefore = before, timeoutMillis = timeout)
        val lock = Any(); val log = StringBuilder(); var truncated = false; var outputChars = 0L
        var logRevision = 0L; var flushedRevision = 0L
        fun retainedLog(): String = if (!truncated) log.toString() else log.substring(0, 64000) + "\n[... middle omitted; original characters=$outputChars; retained head=64000 and tail=448000 ...]\n" + log.substring(64000)
        suspend fun saveRecord() = withContext(Dispatchers.IO) { record(id, run) }
        activeRunId = run.id
        try {
            saveRecord()
            if (mutable.value.projectId == id) mutable.update { it.copy(runs = (listOf(run) + it.runs).take(20), log = "") }
            try {
                val result = coroutineScope {
                    // A burst may be followed by silence: flushing cannot depend on another chunk arriving.
                    val flusher = launch(Dispatchers.IO) {
                        while (isActive) {
                            delay(500)
                            val snapshot = synchronized(lock) { if (logRevision == flushedRevision) null else logRevision to retainedLog() }
                            if (snapshot != null) {
                                PrivateFiles.writeConfined(dir(id), run.id + ".log", AiLogRedactor("").clean(snapshot.second))
                                synchronized(lock) { flushedRevision = snapshot.first }
                            }
                        }
                    }
                    try {
                        executeCommand(id, script, timeout) { chunk ->
                            synchronized(lock) {
                                outputChars += chunk.length
                                log.append(chunk)
                                if (log.length > 512000) { log.delete(64000, 64000 + log.length - 512000); truncated = true }
                                logRevision++
                                if (mutable.value.projectId == id) mutable.update { it.copy(log = log.takeLast(30000).toString()) }
                            }
                            output(chunk)
                        }
                    } finally {
                        // An in-flight old snapshot must finish before the final log is sealed below.
                        withContext(NonCancellable) { flusher.cancelAndJoin() }
                    }
                }
                run = run.copy(status = if (result.exitCode == 0) "passed" else "failed", exit = result.exitCode)
                return result.copy(runId = run.id)
            } catch (timeoutError: TimeoutCancellationException) { run = run.copy(status = "timeout"); currentCoroutineContext().ensureActive(); throw ProjectCommandFailure(run.id, true, timeoutError) }
            catch (cancelled: CancellationException) { run = run.copy(status = "cancelled"); throw cancelled }
            catch (error: Exception) { run = run.copy(status = "failed"); throw ProjectCommandFailure(run.id, false, error) }
            finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    val elapsed = System.currentTimeMillis() - run.started
                    val generation = invalidateEvidence()
                    val after = try { if (inspectSource()) captureSource(id) else null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    run = run.copy(elapsed = elapsed, truncated = truncated, outputChars = outputChars, sourceAfter = after)
                    val finalLog = synchronized(lock) { retainedLog() }
                    PrivateFiles.writeConfined(dir(id), run.id + ".log", AiLogRedactor("").clean(finalLog))
                    record(id, run)
                    mutable.update { state -> if (state.projectId != id) state else state.copy(runs = (listOf(run) + state.runs.filterNot { r -> r.id == run.id }).take(20),
                        currentSource = if (generation == evidenceGeneration.get()) after else state.currentSource,
                        sourceCheckedAt = if (generation == evidenceGeneration.get()) System.currentTimeMillis() else state.sourceCheckedAt) }
                }
            }
        } finally {
            // Including an initial/final storage failure: disk state alone cannot prove a live owner.
            if (activeRunId == run.id) activeRunId = null
        }
    }
    suspend fun logPage(id: String, runId: String, offset: Int = 0, limit: Int = 12000, query: String = ""): String = withContext(Dispatchers.IO) {
        require(offset >= 0 && limit in 1..30000 && query.length <= 200)
        val run = runs(id).single { it.id == runId }
        val text = log(id, runId)
        require(offset <= text.length)
        val match = if (query.isEmpty()) offset else text.indexOf(query, offset, ignoreCase = true)
        val start = if (match < 0) offset else maxOf(offset, match - if (query.isEmpty()) 0 else minOf(1000, limit / 4))
        val end = if (match < 0) start else minOf(text.length, start + limit)
        JSONObject().put("run_id", runId).put("status", run.status).put("exit_code", run.exit ?: JSONObject.NULL)
            .put("retained_characters", text.length).put("original_characters", run.outputChars)
            .put("log_truncated", run.truncated).put("offset", start).put("next_offset", end)
            .put("query_found", match >= 0).put("match_offset", if (match >= 0) match else JSONObject.NULL).put("text", text.substring(start, end)).toString()
    }
    suspend fun log(id: String, runId: String): String = withContext(Dispatchers.IO) {
        require(UUID.fromString(runId).toString() == runId); val file = File(dir(id), runId + ".log"); require(file.length() <= 2 * 1024 * 1024)
        file.takeIf(File::exists)?.readText().orEmpty()
    }
}
