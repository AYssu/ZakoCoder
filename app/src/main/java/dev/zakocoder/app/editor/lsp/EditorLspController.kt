package dev.zakocoder.app.editor.lsp

import android.content.Context
import dev.zakocoder.app.runtime.LinuxRuntime
import dev.zakocoder.app.settings.AppSettings
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class LspPhase { Idle, Unsupported, RuntimeUnavailable, Checking, MissingServer, Starting, Ready, Failed }
data class EditorLspState(val phase: LspPhase = LspPhase.Idle, val server: LspServer? = null, val diagnosticCount: Int = 0, val code: String = "", val projectId: String? = null, val path: String = "", val stderr: String = "")

/** A single visible document owns a server. Switching project/file releases the previous process. */
class EditorLspController(
    context: Context,
    private val runtime: LinuxRuntime,
    private val settings: () -> AppSettings,
    private val available: () -> Boolean,
) {
    private val hostDirectory = context.filesDir
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(EditorLspState())
    val state = mutable.asStateFlow()
    @Volatile private var generation = 0L
    private var pending: Job? = null
    private var session: Session? = null
    private var owner: WeakReference<CodeEditor>? = null
    private data class Session(val projectId: String, val path: String, val view: WeakReference<CodeEditor>, val project: LspProject,
        val editor: LspEditor, val connection: UbuntuLspConnection, val languageFactory: () -> Language, val released: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean())

    /** Supply a NEW syntax Language instance: CodeEditor destroys its previous Language on replace. */
    fun attach(view: CodeEditor, projectId: String, path: String, languageFactory: () -> Language) {
        // Retrying the same visible view must leave a usable base language even when the
        // new probe cannot connect. A different view will release its old language itself.
        detach(restoreSyntax = owner?.get() === view)
        owner = WeakReference(view)
        val ticket = generation
        val server = LspServer.forPath(path) ?: run { mutable.value = EditorLspState(LspPhase.Unsupported, projectId = projectId, path = path); return }
        if (!available()) { mutable.value = EditorLspState(LspPhase.RuntimeUnavailable, server, projectId = projectId, path = path); return }
        mutable.value = EditorLspState(LspPhase.Checking, server, projectId = projectId, path = path)
        pending = scope.launch {
            var created: Session? = null
            try {
                val document = lspDocumentPath(projectId, path)
                val exists = try { runtime.execute(server.detectScript, projectId, settings(), timeoutMillis = 20_000) }
                catch (timeout: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    if (ticket == generation) mutable.value = mutable.value.copy(phase = LspPhase.Failed, code = "lsp_detect_timeout")
                    return@launch
                }
                if (ticket != generation) return@launch
                if (exists.exitCode != 0) { mutable.value = mutable.value.copy(phase = LspPhase.MissingServer, code = "lsp_server_missing"); return@launch }
                val command = runtime.command(projectId, settings(), server.script)
                ensureActive()
                val project = LspProject(lspProjectPath(projectId))
                val extension = path.substringAfterLast('.')
                val wire = LspWireAdapter(lspProjectPath(projectId), document, server.languageFor(extension)) { diagnosticPath ->
                    if (diagnosticPath == document) project.diagnosticsContainer.setDiagnostics(io.github.rosemoe.sora.lsp.utils.FileUri(document), mutableListOf())
                }
                val connection = UbuntuLspConnection(command, hostDirectory, { text ->
                    val safe = text.takeLast(8000).replace(Regex("(?i)(https?://)[^\\s/@]+(?::[^\\s/@]*)?@"), "$1[redacted]@")
                    mutable.update { current -> if (ticket == generation && current.projectId == projectId && current.path == path) current.copy(stderr = safe) else current }
                }, wire)
                project.addServerDefinition(CustomLanguageServerDefinition(extension, mapOf(extension to server.languageFor(extension)),
                    CustomLanguageServerDefinition.ServerConnectProvider { connection }))
                val editor = project.createEditor(document)
                val active = Session(projectId, path, WeakReference(view), project, editor, connection, languageFactory)
                created = active
                session = active
                editor.wrapperLanguage = languageFactory()
                editor.editor = view
                mutable.value = mutable.value.copy(phase = LspPhase.Starting)
                try { withTimeout(25_000) { editor.connect() } }
                catch (timeout: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    if (ticket == generation) {
                        mutable.value = mutable.value.copy(phase = LspPhase.Failed, code = "lsp_connect_timeout")
                        view.setEditorLanguage(languageFactory())
                    }
                    return@launch
                }
                ensureActive()
                if (ticket != generation) return@launch
                mutable.value = mutable.value.copy(phase = LspPhase.Ready, diagnosticCount = editor.diagnostics.size)
                while (isActive && ticket == generation && session === active) {
                    delay(500)
                    if (!connection.isAlive) {
                        mutable.value = mutable.value.copy(phase = LspPhase.Failed, code = "lsp_server_exited")
                        view.setEditorLanguage(languageFactory())
                        break
                    }
                    mutable.value = mutable.value.copy(diagnosticCount = editor.diagnostics.size)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ticket == generation) {
                    mutable.value = mutable.value.copy(phase = LspPhase.Failed, code = "lsp_connect_failed")
                    created?.view?.get()?.setEditorLanguage(languageFactory())
                }
            } finally {
                val active = created
                if (active != null && (ticket != generation || mutable.value.phase != LspPhase.Ready)) {
                    if (session === active) session = null
                    release(active)
                }
            }
        }
    }
    fun save(projectId: String, path: String) {
        val active = session?.takeIf { it.projectId == projectId && it.path == path } ?: return
        scope.launch {
            try { active.editor.saveDocument() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (session === active) mutable.value = mutable.value.copy(code = "lsp_save_notification_failed") }
        }
    }
    fun detach(view: CodeEditor, restoreSyntax: Boolean = false) {
        if (owner?.get() === view) detach(restoreSyntax)
    }
    fun detach(restoreSyntax: Boolean = true) {
        generation++
        owner = null
        // Main.immediate cancellation can run the pending job's finally immediately and
        // clear session. Capture/remove the UI owner first, before cancellation is delivered.
        val active = session; session = null
        val previous = pending; pending = null
        previous?.cancel()
        if (active != null) {
            if (restoreSyntax) active.view.get()?.setEditorLanguage(active.languageFactory())
            scope.launch(start = CoroutineStart.UNDISPATCHED) { release(active) }
        }
        mutable.value = EditorLspState()
    }
    fun close() { detach(); scope.coroutineContext.cancelChildren() }
    private suspend fun release(active: Session) = withContext(NonCancellable + Dispatchers.IO) {
        // Close the transport before Sora shutdown, so blocked initialization cannot respawn it.
        if (!active.released.compareAndSet(false, true)) return@withContext
        active.connection.close()
        runCatching { active.project.dispose() }
    }

    /** Uses the same real request manager as Sora's completion popup. No fake completion list. */
    suspend fun completionLabels(line: Int, column: Int): List<String> {
        require(line >= 0 && column >= 0)
        val active = session ?: return emptyList()
        val response = active.editor.requestManager?.completion(CompletionParams(
            TextDocumentIdentifier(lspFileUri(active.projectId, active.path)), Position(line, column)))?.let { future -> withTimeout(15_000) { future.awaitLsp() } } ?: return emptyList()
        return (if (response.isLeft) response.left else response.right.items).map { it.label }.take(500)
    }
}

private suspend fun <T> CompletableFuture<T>.awaitLsp(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { value, error ->
        if (continuation.isActive) {
            if (error == null) continuation.resume(value) else continuation.resumeWithException(error)
        }
    }
    continuation.invokeOnCancellation { cancel(true) }
}
