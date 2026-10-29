package dev.zakocoder.app.projects

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.core.content.edit
import dev.zakocoder.app.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID

 data class Project(val id: String, val name: String, val kinds: Set<ProjectKind>)
 data class ProjectFile(val name: String, val relative: String, val directory: Boolean, val sizeBytes: Long = 0, val modifiedAt: Long = 0)

/** All methods that touch documents or disk are called by the controller on Dispatchers.IO. */
class ProjectRepository(private val context: Context, val base: File) {
    private val registry = AtomicFile(File(context.filesDir, "projects-v1.json"))
    private val preferences = context.getSharedPreferences("projects", Context.MODE_PRIVATE)
    fun load(): List<Project> {
        if (!registry.baseFile.exists()) return emptyList()
        val json = JSONObject(registry.openRead().bufferedReader().use { it.readText() })
        require(json.getInt("schemaVersion") == 1)
        val projects = json.getJSONArray("projects")
        return (0 until projects.length()).map { index ->
            val item = projects.getJSONObject(index)
            val id = item.getString("id")
            require(UUID.fromString(id).toString() == id)
            val kinds = item.getJSONArray("kinds")
            Project(id, item.getString("name"), (0 until kinds.length()).map { ProjectKind.valueOf(kinds.getString(it)) }.toSet())
        }.filter { File(base, it.id).isDirectory }
    }
    fun save(projects: List<Project>) {
        val json = JSONObject().put("schemaVersion", 1).put("projects", JSONArray().apply {
            projects.forEach { project -> put(JSONObject().put("id", project.id).put("name", project.name)
                .put("kinds", JSONArray(project.kinds.map { it.name }))) }
        })
        val stream = registry.startWrite()
        try { stream.write(json.toString().toByteArray()); registry.finishWrite(stream) }
        catch (error: Exception) { registry.failWrite(stream); throw error }
    }
    fun selected(): String? = preferences.getString("selected", null)
    fun select(id: String?) { preferences.edit { if (id == null) remove("selected") else putString("selected", id) } }
    fun directory(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(base, id).also {
            require(it.isDirectory && !Files.isSymbolicLink(it.toPath()) &&
                it.canonicalFile.toPath().startsWith(base.canonicalFile.toPath())) { context.getString(R.string.directory_unreadable) }
        }
    }
    fun create(name: String, templateFiles: Map<String, String> = emptyMap()): Project {
        require(name.isNotBlank() && name.length <= 80 && name.none { it == '/' || it == '\\' || it.isISOControl() }) {
            context.getString(R.string.project_name_invalid)
        }
        val project = Project(UUID.randomUUID().toString(), name.trim(), emptySet())
        val root = File(base, project.id)
        check(root.mkdirs())
        // The writer validates, writes in UTF-8 and rolls back the whole root itself on failure.
        if (templateFiles.isNotEmpty()) {
            try {
                ProjectTemplateWriter.write(root, templateFiles)
            } catch (invalid: IllegalArgumentException) {
                // Surface the user-facing scaffold error while keeping the writer's rollback intact.
                throw IllegalArgumentException(context.getString(R.string.project_template_invalid), invalid)
            }
        }
        return project
    }
    suspend fun import(tree: Uri, progress: (Int) -> Unit): Project {
        val resolver = context.contentResolver
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)!!.use {
            check(it.moveToFirst()); it.getString(0)
        }
        val project = create(name)
        val root = directory(project.id)
        var entries = 0
        var bytes = 0L
        val coroutine = currentCoroutineContext()
        fun copy(parent: Uri, target: File, depth: Int) {
            require(depth <= 40)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)!!.use { cursor ->
                while (cursor.moveToNext()) {
                    coroutine.ensureActive()
                    require(++entries <= 20000) { context.getString(R.string.project_import_limit) }
                    val childName = cursor.getString(1)
                    require(childName.isNotEmpty() && childName !in setOf(".", "..") && childName.none { it == '/' || it == '\u0000' })
                    if (childName in ProjectDetector.excluded) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                    val file = File(target, childName)
                    require(!file.exists()) { "Duplicate document name" }
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        check(file.mkdir()); copy(uri, file, depth + 1)
                    } else {
                        resolver.openInputStream(uri)!!.use { input -> file.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                coroutine.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                require(bytes <= 512L * 1024 * 1024) { context.getString(R.string.project_import_limit) }
                                output.write(buffer, 0, count)
                            }
                        } }
                        if (childName in setOf("gradlew", "mvnw") || childName.endsWith(".sh")) check(file.setExecutable(true, true))
                    }
                    if (entries % 20 == 0) progress(entries)
                }
            }
        }
        return try { copy(document, root, 0); project.copy(kinds = ProjectDetector.detect(root)) }
        catch (error: Exception) { root.deleteRecursively(); throw error }
    }
    fun resolve(id: String, relative: String): File {
        val root = directory(id).canonicalFile
        val file = File(root, relative).canonicalFile
        require(file == root || file.toPath().startsWith(root.toPath()))
        return file
    }
    fun files(id: String, relative: String): List<ProjectFile> = (resolve(id, relative).listFiles() ?: error(context.getString(R.string.directory_unreadable)))
        .filterNot { Files.isSymbolicLink(it.toPath()) }
        .map { ProjectFile(it.name, listOf(relative, it.name).filter(String::isNotEmpty).joinToString("/"), it.isDirectory, if (it.isFile) it.length() else 0, it.lastModified()) }
        .sortedWith(compareByDescending<ProjectFile> { it.directory }.thenBy { it.name.lowercase() })
    private fun newFile(id: String, relative: String, name: String): File {
        require(name.isNotBlank() && name.length <= 255 && name !in setOf(".", "..") &&
            name.none { it == '/' || it == '\\' || it.isISOControl() }) { context.getString(R.string.file_name_invalid) }
        val parent = resolve(id, relative)
        require(parent.isDirectory) { context.getString(R.string.directory_unreadable) }
        return File(parent, name).also {
            require(!Files.exists(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { context.getString(R.string.file_already_exists) }
        }
    }
    fun createFile(id: String, relative: String, name: String) {
        Files.createFile(newFile(id, relative, name).toPath())
    }
    suspend fun importFile(id: String, relative: String, uri: Uri) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: error(context.getString(R.string.file_name_invalid))
        val target = newFile(id, relative, name)
        val temporary = File.createTempFile(".zakocoder-import-", ".tmp", target.parentFile)
        try {
            val coroutine = currentCoroutineContext()
            resolver.openInputStream(uri)!!.use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var bytes = 0L
                while (true) {
                    coroutine.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= 512L * 1024 * 1024) { context.getString(R.string.project_import_limit) }
                    output.write(buffer, 0, count)
                }
            } }
            coroutine.ensureActive()
            Files.move(temporary.toPath(), target.toPath())
        } finally { temporary.delete() }
    }
    private fun existingEntry(id: String, relative: String): File {
        require(relative.isNotEmpty() && !relative.startsWith('/') && !relative.contains('\\')) { context.getString(R.string.file_name_invalid) }
        var requested = directory(id)
        relative.split('/').forEach { part ->
            require(part.isNotEmpty() && part !in setOf(".", "..") && part.none(Char::isISOControl)) { context.getString(R.string.file_name_invalid) }
            requested = File(requested, part)
            require(!Files.isSymbolicLink(requested.toPath())) { context.getString(R.string.editor_path_invalid) }
        }
        require(Files.exists(requested.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { context.getString(R.string.editor_path_invalid) }
        return requested
    }
    fun renameEntry(id: String, relative: String, name: String) {
        val source = existingEntry(id, relative)
        val destination = newFile(id, relative.substringBeforeLast('/', ""), name)
        Files.move(source.toPath(), destination.toPath())
    }
    suspend fun copyEntry(id: String, relative: String, name: String) {
        val source = existingEntry(id, relative)
        require(source.isFile) { context.getString(R.string.file_copy_regular) }
        val target = newFile(id, relative.substringBeforeLast('/', ""), name)
        val temporary = File.createTempFile(".zakocoder-copy-", ".tmp", requireNotNull(target.parentFile))
        try {
            val coroutine = currentCoroutineContext()
            Files.newInputStream(source.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(65536); var bytes = 0L
                while (true) {
                    coroutine.ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    bytes += count; require(bytes <= 512L * 1024 * 1024) { context.getString(R.string.project_import_limit) }
                    output.write(buffer, 0, count)
                }
            } }
            coroutine.ensureActive()
            Files.setPosixFilePermissions(temporary.toPath(), Files.getPosixFilePermissions(source.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            Files.move(temporary.toPath(), target.toPath())
        } finally { temporary.delete() }
    }
    fun deleteEntry(id: String, relative: String) {
        dev.zakocoder.app.runtime.PrivateFiles.deleteTree(existingEntry(id, relative))
    }
    fun preview(id: String, relative: String): String {
        val file = resolve(id, relative)
        require(file.isFile && file.length() <= 65536) { context.getString(R.string.preview_limit) }
        val text = file.readText()
        require('\u0000' !in text) { context.getString(R.string.preview_binary) }
        return text
    }
    suspend fun export(id: String, tree: Uri): String {
        val resolver = context.contentResolver
        val target = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val outputName = "ZakoCoder-${id.take(8)}-${UUID.randomUUID().toString().take(8)}"
        val created = DocumentsContract.createDocument(resolver, target, DocumentsContract.Document.MIME_TYPE_DIR, outputName)!!
        val coroutine = currentCoroutineContext()
        var entries = 0
        fun copy(source: File, output: Uri, depth: Int) {
            coroutine.ensureActive()
            require(depth <= 40 && ++entries <= 20000)
            (source.listFiles() ?: error(context.getString(R.string.directory_unreadable))).forEach { file ->
                coroutine.ensureActive()
                if (file.name in ProjectDetector.excluded || Files.isSymbolicLink(file.toPath())) return@forEach
                if (file.isDirectory) {
                    val child = DocumentsContract.createDocument(resolver, output, DocumentsContract.Document.MIME_TYPE_DIR, file.name)!!
                    copy(file, child, depth + 1)
                } else {
                    require(file.length() <= 512L * 1024 * 1024)
                    val child = DocumentsContract.createDocument(resolver, output, "application/octet-stream", file.name)!!
                    file.inputStream().use { input -> resolver.openOutputStream(child, "w")!!.use { stream ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            coroutine.ensureActive(); val count = input.read(buffer)
                            if (count < 0) break
                            stream.write(buffer, 0, count)
                        }
                    } }
                }
            }
        }
        try { copy(directory(id), created, 0) }
        catch (error: Exception) {
            try { DocumentsContract.deleteDocument(resolver, created) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
        return outputName
    }
}
