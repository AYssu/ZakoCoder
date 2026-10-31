package dev.zakocoder.app.editor

import android.content.Context
import dev.zakocoder.app.R
import dev.zakocoder.app.projects.ProjectRepository
import io.github.rosemoe.sora.text.Content
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class EditorDocument(var baseline: FileBaseline) {
    val content = Content(baseline.text)
    var line = 0
    var column = 0
}

data class EditorTab(val projectId: String, val path: String, val dirty: Boolean)
enum class EditorDiskState { Unchanged, Changed, Unavailable }

data class EditorState(
    val tabs: List<EditorTab> = emptyList(), val projectId: String? = null, val path: String = "", val document: EditorDocument? = null,
    val loading: Boolean = false, val saving: Boolean = false, val dirty: Boolean = false,
    val revision: Int = 0, val error: String = "", val conflict: Boolean = false,
    val canUndo: Boolean = false, val canRedo: Boolean = false, val saved: Boolean = false,
    val diskState: EditorDiskState? = null, val diskChecking: Boolean = false, val diskVersion: FileBaseline? = null,
)

/** Application-owned buffer; Activity recreation releases the View, preserving Content/undo/cursor. */
class EditorController(private val context: Context, private val projects: ProjectRepository, private val savedFile: () -> Unit,
    private val inspectDisk: suspend (FileBaseline) -> FileBaseline = { EditorFileStore(projects::directory).open(it.projectId, it.path) },
    val lsp: dev.zakocoder.app.editor.lsp.EditorLspController? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val files = EditorFileStore(projects::directory)
    val languages = EditorLanguages(context)
    private val mutable = MutableStateFlow(EditorState())
    val state = mutable.asStateFlow()
    private val documents = linkedMapOf<Pair<String, String>, EditorDocument>()
    private val drafts = EditorDraftStore(java.io.File(context.noBackupFilesDir, "editor-drafts"))
    private val draftMutex = kotlinx.coroutines.sync.Mutex()
    private var draftJob: Job? = null
    private var diskJob: Job? = null
    private var diskGeneration = 0L
    private fun invalidateDisk() { diskGeneration++; diskJob?.cancel(); diskJob = null }
    private fun tabs() = documents.map { (key, doc) -> EditorTab(key.first, key.second, doc.content.toString() != doc.baseline.text) }
    init {
        mutable.update { it.copy(loading = true) }
        scope.launch {
            try {
                val restored = withContext(Dispatchers.IO) { languages.prepare(); drafts.load() }
                restored.forEach { d ->
                    val valid = withContext(Dispatchers.IO) { runCatching { projects.directory(d.baseline.projectId); AiSafeDraftPath.validate(d.baseline.path) }.isSuccess }
                    if (valid) documents[d.baseline.projectId to d.baseline.path] = EditorDocument(d.baseline).apply { content.replace(0, content.length, d.text); line = d.line; column = d.column }
                }
                mutable.update { it.copy(tabs = tabs(), loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loading = false, error = context.getString(R.string.editor_draft_error)) } }
        }
    }
    private fun queueDrafts(immediate: Boolean = false) {
        draftJob?.cancel()
        draftJob = scope.launch {
            if (!immediate) delay(500)
            val snapshot = documents.values.filter { it.content.toString() != it.baseline.text }.map { EditorDraft(it.baseline, it.content.toString(), it.line, it.column) }
            try { draftMutex.lock(); try { withContext(Dispatchers.IO) { drafts.save(snapshot) } } finally { draftMutex.unlock() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = context.getString(R.string.editor_draft_error)) } }
        }
    }
    suspend fun listFiles(id: String, directory: String) = withContext(Dispatchers.IO) { projects.files(id, directory) }
    fun isOpen(id: String, path: String) = mutable.value.tabs.any { it.projectId == id && it.path == path }
    fun open(id: String, path: String) {
        if (mutable.value.loading || mutable.value.saving) return
        val doc = documents[id to path]
        if (doc != null) {
            mutable.value = EditorState(tabs(), id, path, doc, dirty = doc.content.toString() != doc.baseline.text, canUndo = doc.content.canUndo(), canRedo = doc.content.canRedo())
            checkDisk(); return
        }
        if (documents.size >= 8) { mutable.update { it.copy(error = context.getString(R.string.editor_tabs_limit)) }; return }
        invalidateDisk()
        mutable.value = EditorState(tabs = tabs(), projectId = id, path = path, loading = true)
        load(id, path)
    }
    /** A sampled comparison only. Never replaces a user's buffer or rebases its save protection. */
    fun checkDisk(): Job? {
        val state = mutable.value
        val doc = state.document ?: return null
        if (state.loading || state.saving) return null
        invalidateDisk()
        val generation = diskGeneration
        val baseline = doc.baseline
        mutable.update { it.copy(diskChecking = true) }
        return scope.launch {
            try {
                val disk = withContext(Dispatchers.IO) { inspectDisk(baseline) }
                if (generation == diskGeneration && mutable.value.document === doc && doc.baseline == baseline) {
                    val changed = disk.digest != baseline.digest
                    mutable.update { it.copy(diskState = if (changed) EditorDiskState.Changed else EditorDiskState.Unchanged, diskVersion = if (changed) disk else null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == diskGeneration && mutable.value.document === doc && doc.baseline == baseline)
                    mutable.update { it.copy(diskState = EditorDiskState.Unavailable, diskVersion = null) }
            } finally {
                if (generation == diskGeneration && mutable.value.document === doc) mutable.update { it.copy(diskChecking = false) }
            }
        }.also { diskJob = it }
    }
    private fun message(error: Exception): String = when ((error as? EditorFileException)?.reason) {
        EditorFileException.Reason.UNSUPPORTED -> context.getString(R.string.editor_unsupported)
        EditorFileException.Reason.LIMIT -> context.getString(R.string.editor_limit)
        EditorFileException.Reason.PATH -> context.getString(R.string.editor_path_invalid)
        EditorFileException.Reason.CONFLICT -> context.getString(R.string.editor_conflict_message)
        null -> context.getString(R.string.editor_io_error)
    }
    private fun load(id: String, path: String) {
        scope.launch {
            try {
                val baseline = withContext(Dispatchers.IO) { languages.prepare(); files.open(id, path) }
                val doc = EditorDocument(baseline)
                documents[id to path] = doc
                mutable.value = EditorState(tabs = tabs(), projectId = id, path = path, document = doc, diskState = EditorDiskState.Unchanged)
                queueDrafts(immediate = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(loading = false, error = message(error), diskChecking = false, diskState = if (it.document != null) EditorDiskState.Unavailable else null, diskVersion = null) } }
        }
    }
    fun changed() {
        val doc = mutable.value.document ?: return
        mutable.update { it.copy(dirty = doc.content.toString() != doc.baseline.text, canUndo = doc.content.canUndo(), canRedo = doc.content.canRedo(),
            revision = it.revision + 1, saved = false, tabs = tabs()) }
        queueDrafts()
    }
    fun save(closeAfter: Boolean = false, keepEditorOpen: Boolean = false) {
        val current = mutable.value
        val doc = current.document ?: return
        if (current.saving || current.loading) return
        if (!current.dirty) { if (closeAfter) close(keepEditorOpen); return }
        invalidateDisk()
        val text = doc.content.toString()
        mutable.update { it.copy(saving = true, error = "", saved = false, diskChecking = false, diskState = null, diskVersion = null) }
        scope.launch {
            try {
                val baseline = withContext(Dispatchers.IO) { files.save(doc.baseline, text) }
                doc.baseline = baseline
                savedFile()
                mutable.update { it.copy(saving = false, dirty = doc.content.toString() != text, error = "", conflict = false, saved = true, diskState = null, diskChecking = false, diskVersion = null) }
                lsp?.save(doc.baseline.projectId, doc.baseline.path)
                checkDisk()
                mutable.update { it.copy(tabs = tabs()) }; queueDrafts(immediate = true)
                if (closeAfter && !mutable.value.dirty) close(keepEditorOpen)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(saving = false, error = message(error), conflict = (error as? EditorFileException)?.reason == EditorFileException.Reason.CONFLICT, diskChecking = false) }; checkDisk() }
        }
    }
    fun replaceAll(query: String, replacement: String) {
        val doc = mutable.value.document ?: return
        if (query.isEmpty() || mutable.value.saving || mutable.value.loading) return
        val text = doc.content.toString()
        var count = 0
        var from = 0
        while (true) {
            val index = text.indexOf(query, from)
            if (index < 0) break
            count++; from = index + query.length
        }
        if (count == 0) return
        val length = text.length.toLong() + count.toLong() * (replacement.length - query.length)
        if (length > EditorFileStore.MAX_BYTES) {
            mutable.update { it.copy(error = context.getString(R.string.editor_limit)) }; return
        }
        val result = text.replace(query, replacement)
        if (result.toByteArray(Charsets.UTF_8).size > EditorFileStore.MAX_BYTES) {
            mutable.update { it.copy(error = context.getString(R.string.editor_limit)) }; return
        }
        doc.content.replace(0, doc.content.length, result)
        changed()
    }
    fun reload() {
        val current = mutable.value
        if (current.loading || current.saving) return
        val id = current.projectId ?: return
        invalidateDisk()
        mutable.update { it.copy(loading = true, error = "", diskChecking = false, diskState = null, diskVersion = null) }
        load(id, current.path)
    }
    fun close(keepEditorOpen: Boolean = false) {
        if (!mutable.value.loading && !mutable.value.saving) {
            invalidateDisk()
            val projectId = mutable.value.projectId
            documents.remove(projectId to mutable.value.path)
            val next = if (keepEditorOpen) documents.keys.lastOrNull { it.first == projectId } else null
            mutable.value = EditorState(tabs = tabs())
            if (next != null) open(next.first, next.second)
            queueDrafts(immediate = true)
        }
    }
}

private object AiSafeDraftPath {
    fun validate(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.none(Char::isISOControl) })
    }
}
