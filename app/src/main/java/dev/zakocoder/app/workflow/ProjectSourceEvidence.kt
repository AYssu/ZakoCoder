package dev.zakocoder.app.workflow

import dev.zakocoder.app.ai.AiProjectFiles
import dev.zakocoder.app.ai.AiProjectIndex
import dev.zakocoder.app.ai.isSensitiveProjectName
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

data class ProjectSourceStamp(val digest: String, val limited: Boolean, val files: Int, val directories: Int) {
    fun encode() = JSONObject().put("version", 1).put("digest", digest).put("limited", limited).put("files", files).put("directories", directories)
    companion object {
        fun decode(json: JSONObject?): ProjectSourceStamp? = try {
            if (json == null || json.getInt("version") != 1) null else ProjectSourceStamp(json.getString("digest"), json.getBoolean("limited"), json.getInt("files"), json.getInt("directories")).also {
                require(it.digest.matches(Regex("[a-f0-9]{64}")) && it.files in 0..500 && it.directories in 0..500)
            }
        } catch (_: Exception) { null }
    }
}

enum class ProjectVerification { Unchanged, Changed, ChangedDuringRun, Unknown, Pending }

fun ProjectRun.configuration(commands: List<ProjectCommand>): String {
    if (kind !in setOf("build", "test", "run")) return "not_configured_command"
    val command = commands.firstOrNull { it.kind == kind } ?: return "changed"
    if (script != command.shell()) return "changed"
    if (timeoutMillis == 0L) return "timeout_unknown"
    return if (timeoutMillis == command.timeoutSeconds * 1000L) "unchanged" else "timeout_changed"
}

fun ProjectRun.verification(current: ProjectSourceStamp?): ProjectVerification {
    if (status == "running") return ProjectVerification.Pending
    val before = sourceBefore ?: return ProjectVerification.Unknown
    val after = sourceAfter ?: return ProjectVerification.Unknown
    if (before.limited || after.limited) return ProjectVerification.Unknown
    if (before.digest != after.digest) return ProjectVerification.ChangedDuringRun
    if (current == null || current.limited) return ProjectVerification.Unknown
    if (after.digest != current.digest) return ProjectVerification.Changed
    return ProjectVerification.Unchanged
}

/** Local evidence only: hashes bytes without retaining content. Caller supplies IO and cancellation. */
internal class ProjectSourceEvidence(private val root: (String) -> File) {
    fun capture(id: String, coroutine: CoroutineContext): ProjectSourceStamp {
        val safe = AiProjectFiles(root)
        val digest = MessageDigest.getInstance("SHA-256")
        var limited = false; var seen = 0; var files = 0; var directories = 0; var bytes = 0L; var pathBytes = 0
        fun add(value: String) {
            val encoded = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(encoded.size).array()); digest.update(encoded)
        }
        fun visit(path: String, depth: Int) {
            coroutine.ensureActive()
            if (depth > 32 || seen >= 4000) { limited = true; return }
            val target = try { safe.resolve(id, path, true) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { limited = true; return }
            try {
                val mode = Files.getPosixFilePermissions(target.toPath(), NOFOLLOW_LINKS).sortedBy { it.name }.joinToString { it.name }
                if (Files.isDirectory(target.toPath(), NOFOLLOW_LINKS)) {
                    if (directories >= 500) { limited = true; return }
                    directories++; add(path); add("directory"); add(mode)
                    val children = mutableListOf<File>()
                    Files.newDirectoryStream(target.toPath()).use { stream ->
                        for (child in stream) {
                            coroutine.ensureActive()
                            if (children.size >= 4000 - seen) { limited = true; break }
                            children += child.toFile()
                        }
                    }
                    for (child in children.sortedBy { it.name }) {
                        coroutine.ensureActive()
                        if (++seen > 4000) { limited = true; return }
                        if (child.name in AiProjectIndex.excluded || child.name in setOf(".pytest_cache", ".mypy_cache", ".ruff_cache") || isSensitiveProjectName(child.name)) continue
                        val relative = if (path.isEmpty()) child.name else "$path/${child.name}"
                        // Sensitive names use the existing file-tool policy; never inspect their body.
                        if (Files.isSymbolicLink(child.toPath())) { limited = true; continue }
                        try { safe.resolve(id, relative) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: IllegalArgumentException) { limited = true; continue }
                        pathBytes += relative.toByteArray(Charsets.UTF_8).size
                        if (pathBytes > 200000) { limited = true; return }
                        visit(relative, depth + 1)
                    }
                } else if (Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS)) {
                    if (files >= 500) { limited = true; return }
                    val fileDigest = MessageDigest.getInstance("SHA-256")
                    Files.newByteChannel(target.toPath(), setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                        if (channel.size() > 1048576 || bytes + channel.size() > 16 * 1024 * 1024) { limited = true; return }
                        val buffer = ByteBuffer.allocate(8192); var length = 0
                        while (channel.read(buffer) != -1) {
                            coroutine.ensureActive(); length += buffer.position(); bytes += buffer.position()
                            if (length > 1048576 || bytes > 16 * 1024 * 1024) { limited = true; return }
                            buffer.flip(); fileDigest.update(buffer); buffer.clear()
                        }
                    }
                    files++; add(path); add("file"); add(mode); add(fileDigest.digest().joinToString("") { "%02x".format(it) })
                } else limited = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { limited = true }
        }
        add("project-source-evidence-v1"); visit("", 0)
        return ProjectSourceStamp(digest.digest().joinToString("") { "%02x".format(it) }, limited, files, directories)
    }
}
