package dev.zakocoder.app.ai

import dev.zakocoder.app.editor.EditorFileStore
import dev.zakocoder.app.editor.FileBaseline
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Blocking IO; the controller supplies Dispatchers.IO. */
class AiProjectFiles(private val root: (String) -> File) {
    val store = EditorFileStore(root)
    fun resolve(id: String, path: String, allowRoot: Boolean = false): File {
        if (allowRoot && path.isEmpty()) return root(id)
        if (path.isEmpty() || path.startsWith('/') || path.contains('\\')) throw AiFileFailure(AiFileFailure.Reason.INVALID_PATH)
        var result = root(id)
        path.split('/').forEach { part ->
            if (part.isEmpty() || part == "." || part == ".." || part.any(Char::isISOControl)) throw AiFileFailure(AiFileFailure.Reason.INVALID_PATH)
            if (isSensitiveProjectName(part)) throw AiFileFailure(AiFileFailure.Reason.PROTECTED_PATH)
            result = File(result, part)
            if (Files.isSymbolicLink(result.toPath())) throw AiFileFailure(AiFileFailure.Reason.INVALID_PATH)
        }
        if (!result.canonicalFile.toPath().startsWith(root(id).canonicalFile.toPath())) throw AiFileFailure(AiFileFailure.Reason.INVALID_PATH)
        return result
    }
    fun read(id: String, path: String): FileBaseline { resolve(id, path); return store.open(id, path) }
    fun list(id: String, path: String): String = resolve(id, path, true).listFiles().orEmpty().take(200).mapNotNull { file ->
        try { resolve(id, file.relativeTo(root(id)).invariantSeparatorsPath); file.name + if (file.isDirectory) "/" else "" } catch (_: IllegalArgumentException) { null }
    }.joinToString("\n")
    fun search(id: String, query: String): String {
        require(query.isNotEmpty() && query.length <= 200)
        val results = mutableListOf<String>(); var visited = 0
        fun visit(directory: File, depth: Int) {
            if (depth > 8 || visited >= 2000 || results.size >= 40) return
            for (file in directory.listFiles().orEmpty()) {
                if (++visited > 2000 || results.size >= 40) return
                val path = file.relativeTo(root(id)).invariantSeparatorsPath
                try {
                    resolve(id, path)
                    if (file.name in setOf("node_modules", "build", ".gradle", ".cache")) continue
                    if (file.isDirectory) visit(file, depth + 1)
                    else if (file.length() <= 64000) read(id, path).text.lineSequence().forEachIndexed { index, line ->
                        if (query in line && results.size < 40) results += "$path:${index + 1}:${line.take(300)}"
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* Unreadable/binary/sensitive entries are excluded. */ }
            }
        }
        visit(root(id), 0)
        return results.joinToString("\n")
    }
    fun write(id: String, path: String, before: FileBaseline?, content: String): FileBaseline {
        validateContent(content)
        val file = resolve(id, path)
        return if (before != null) store.save(before, content) else {
            if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) throw AiFileFailure(AiFileFailure.Reason.TARGET_EXISTS)
            if (!file.parentFile.isDirectory) throw AiFileFailure(AiFileFailure.Reason.PARENT_MISSING)
            val temporary = File.createTempFile(".zakocoder-ai-", ".tmp", file.parentFile)
            try {
                val encoded = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(content))
                if (encoded.remaining() > EditorFileStore.MAX_BYTES) throw AiFileFailure(AiFileFailure.Reason.FILE_LIMIT)
                val bytes = ByteArray(encoded.remaining()); encoded.get(bytes)
                java.io.FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
                resolve(id, path)
                Files.move(temporary.toPath(), file.toPath())
                store.open(id, path)
            } finally { temporary.delete() }
        }
    }

    internal fun validateContent(content: String) {
        if ('\u0000' in content) throw AiFileFailure(AiFileFailure.Reason.FILE_UNSUPPORTED)
        if (content.length > EditorFileStore.MAX_BYTES || content.toByteArray(Charsets.UTF_8).size > EditorFileStore.MAX_BYTES ||
            content.lines().size > EditorFileStore.MAX_LINES || content.lineSequence().any { it.length > EditorFileStore.MAX_COLUMN }) {
            throw AiFileFailure(AiFileFailure.Reason.FILE_LIMIT)
        }
    }
}

internal fun isSensitiveProjectName(name: String): Boolean {
    val lower = name.lowercase()
    return lower.startsWith(".env") || lower in setOf(".git", ".ssh", ".aws", ".codex", ".agents", "credentials", "credentials.json", "secrets.json", "local.properties", ".npmrc", ".netrc", ".pypirc", ".git-credentials") ||
        listOf(".pem", ".key", ".p12", ".jks", ".keystore").any(lower::endsWith)
}

/** Observed changes around one approved command; other terminals can also change these files. No undo promise. */
data class AiCommandChange(val id: String, val path: String, val before: String?, val after: String?) {
    fun encode() = org.json.JSONObject().put("id", id).put("path", path).put("before", before).put("after", after)
    companion object {
        fun decode(json: org.json.JSONObject): AiCommandChange {
            val before = if (json.isNull("before")) null else json.getString("before")
            val after = if (json.isNull("after")) null else json.getString("after")
            require((before?.length ?: 0) <= 64000 && (after?.length ?: 0) <= 64000)
            return AiCommandChange(json.getString("id").take(100), json.getString("path").take(500), before, after)
        }
    }
}
data class AiFileSnapshot(val files: Map<String, String>, val limited: Boolean, val existing: Set<String> = files.keys, val listingComplete: Boolean = true)
fun AiProjectFiles.snapshot(id: String): AiFileSnapshot {
    val collected = linkedMapOf<String, String>(); val existing = mutableSetOf<String>(); var seen = 0; var chars = 0; var limited = false; var listingComplete = true
    fun visit(path: String, depth: Int) {
        if (depth > 8) { limited = true; listingComplete = false; return }
        val children = resolve(id, path, true).listFiles() ?: run { limited = true; listingComplete = false; return }
        for (file in children) {
            if (++seen > 2000 || collected.size >= 64 || chars >= 256000) { limited = true; listingComplete = false; return }
            if (file.name in setOf("node_modules", "build", ".gradle", ".cache", ".venv", "venv", "target", "dist", "__pycache__")) continue
            val relative = if (path.isBlank()) file.name else "$path/${file.name}"
            existing += relative
            try {
                val target = resolve(id, relative)
                if (target.isDirectory) visit(relative, depth + 1)
                else if (target.length() <= 64000) {
                    val text = read(id, relative).text
                    if (chars + text.length <= 256000) { collected[relative] = text; chars += text.length } else limited = true
                } else limited = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { limited = true }
        }
    }
    visit("", 0)
    return AiFileSnapshot(collected, limited, existing, listingComplete)
}
